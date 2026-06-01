package parsley;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ng.appserver.templating.parser.model.PBasicNode;
import ng.appserver.templating.parser.model.PNode;
import ng.appserver.templating.parser.model.SourceRange;

/**
 * PROTOTYPE — a per-request render profiler that measures how long each <em>position
 * in the template tree</em> takes to render, so the heat map can show <b>which
 * region of a template is slow</b> rather than merely which element types are slow.
 *
 * <h2>Position, not identity</h2>
 *
 * Element types ({@code wo:str}, {@code wo:if}) are reused all over a template, so
 * "wo:str is slow" isn't actionable. What's actionable is "<em>this</em> repetition,
 * here, containing <em>this</em> component reference, is eating the page". So we key
 * timings on the {@link PNode} <em>identity</em> (its unique spot in the parsed tree)
 * and reconstruct the tree of timed nodes, rather than flattening by element name.
 *
 * <h2>Self-time vs inclusive-time</h2>
 *
 * Each node records both:
 * <ul>
 * <li><b>self-time</b> — work done directly in this node (excludes descendants)</li>
 * <li><b>inclusive-time</b> — self plus all descendants</li>
 * </ul>
 * The tree view uses inclusive-time for a node's bar (so a hot <em>region</em> is
 * visible at a glance even when the cost is spread across children) and self-time to
 * pinpoint the actual culprit once you drill in. We compute self-time by having each
 * child hand its inclusive time up to its parent's frame on exit.
 *
 * <h2>Collapse per node</h2>
 *
 * A node inside a repetition renders many times; all those renders accumulate into
 * the <em>single</em> tree node for that template position (summed time + an
 * occurrence count), so the tree maps 1:1 to the template you'd open in an editor.
 *
 * <h2>Threading</h2>
 *
 * State is per-thread and reset at end of request (WO reuses worker threads). // 2026-06-01
 */
public final class ParsleyRenderProfiler {

	/**
	 * Master switch. When false, every hook is a cheap no-op (a volatile read +
	 * branch) so a project not using profiling pays almost nothing.
	 */
	private static volatile boolean _enabled = false;

	/** Per-request, per-thread profiling state. */
	private static final ThreadLocal<Request> _current = new ThreadLocal<>();

	private ParsleyRenderProfiler() {}

	public static void setEnabled( final boolean enabled ) {
		_enabled = enabled;
	}

	public static boolean isEnabled() {
		return _enabled;
	}

	// =========================================================================
	// Element timing hooks (called by ParsleyProxyElement)
	// =========================================================================

	/**
	 * Marks entry into an element's render phase. Returns a frame token to pass to
	 * {@link #exitElement(Frame)} in a finally block, or null when profiling is off.
	 */
	public static Frame enterElement( final PNode node, final Phase phase ) {
		if( !_enabled ) {
			return null;
		}

		Request request = _current.get();
		if( request == null ) {
			request = new Request();
			_current.set( request );
		}

		// The timed node nested directly above us (top of the live stack) is our
		// tree parent. This is how we reconstruct template structure from runtime.
		final Frame parent = request.stack.isEmpty() ? null : request.stack.get( request.stack.size() - 1 );
		final TreeNode treeNode = request.treeNodeFor( node, phase, parent == null ? null : parent.treeNode );

		final Frame frame = new Frame( treeNode, System.nanoTime() );
		request.stack.add( frame );
		return frame;
	}

	/**
	 * Marks exit from an element's render phase, recording self + inclusive time.
	 * Safe with a null frame.
	 */
	public static void exitElement( final Frame frame ) {
		if( frame == null ) {
			return;
		}
		final Request request = _current.get();
		if( request == null ) {
			return;
		}

		final long inclusive = System.nanoTime() - frame.startNanos;
		final long self = inclusive - frame.childInclusiveNanos;

		frame.treeNode.record( self, inclusive );

		final List<Frame> stack = request.stack;
		if( !stack.isEmpty() && stack.get( stack.size() - 1 ) == frame ) {
			stack.remove( stack.size() - 1 );
		}
		if( !stack.isEmpty() ) {
			stack.get( stack.size() - 1 ).childInclusiveNanos += inclusive;
		}
	}

	// =========================================================================
	// Binding timing hooks (called by ParsleyKeyValueAssociation)
	// =========================================================================

	public static void recordBindingPull( final long nanos ) {
		if( !_enabled ) {
			return;
		}
		final Request request = _current.get();
		if( request != null ) {
			request.bindingPullNanos += nanos;
			request.bindingPullCount++;
			// Credit the binding time to whichever node is currently rendering, so a
			// node's self-time breakdown can show "of which N in bindings".
			if( !request.stack.isEmpty() ) {
				request.stack.get( request.stack.size() - 1 ).treeNode.bindingNanos += nanos;
			}
		}
	}

	public static void recordBindingPush( final long nanos ) {
		if( !_enabled ) {
			return;
		}
		final Request request = _current.get();
		if( request != null ) {
			request.bindingPushNanos += nanos;
			request.bindingPushCount++;
			if( !request.stack.isEmpty() ) {
				request.stack.get( request.stack.size() - 1 ).treeNode.bindingNanos += nanos;
			}
		}
	}

	// =========================================================================
	// Result access + reset (called by ParsleyRequestObserver)
	// =========================================================================

	public static Result takeResult() {
		final Request request = _current.get();
		return request == null ? null : request.toResult();
	}

	public static void reset() {
		_current.remove();
	}

	// =========================================================================
	// Types
	// =========================================================================

	public enum Phase {
		APPEND( "render" ),
		TAKE_VALUES( "take values" ),
		INVOKE_ACTION( "invoke action" );

		private final String _label;

		Phase( final String label ) {
			_label = label;
		}

		public String label() {
			return _label;
		}
	}

	/** Live stack frame for one element render. Mutable; accumulates child time. */
	public static final class Frame {

		private final TreeNode treeNode;
		private final long startNanos;
		private long childInclusiveNanos;

		private Frame( final TreeNode treeNode, final long startNanos ) {
			this.treeNode = treeNode;
			this.startNanos = startNanos;
		}
	}

	/**
	 * A node in the timed template tree. One per (template PNode + phase) position;
	 * accumulates time across every render of that position.
	 */
	public static final class TreeNode {

		private final PNode node;
		private final Phase phase;
		private final String label;
		private final int offset;
		private final List<TreeNode> children = new ArrayList<>();

		private long selfNanos;
		private long inclusiveNanos;
		private long bindingNanos;
		private int count;

		private TreeNode( final PNode node, final Phase phase ) {
			this.node = node;
			this.phase = phase;
			this.label = describe( node );
			this.offset = offsetOf( node );
		}

		private void record( final long self, final long inclusive ) {
			selfNanos += self;
			inclusiveNanos += inclusive;
			count++;
		}

		public String label() {
			return label;
		}

		public int offset() {
			return offset;
		}

		public Phase phase() {
			return phase;
		}

		public long selfNanos() {
			return selfNanos;
		}

		public long inclusiveNanos() {
			return inclusiveNanos;
		}

		public long bindingNanos() {
			return bindingNanos;
		}

		public int count() {
			return count;
		}

		public List<TreeNode> children() {
			return children;
		}

		/** @return children sorted hottest (by inclusive time) first. */
		public List<TreeNode> childrenByHeat() {
			final List<TreeNode> sorted = new ArrayList<>( children );
			sorted.sort( ( a, b ) -> Long.compare( b.inclusiveNanos, a.inclusiveNanos ) );
			return sorted;
		}
	}

	/** Per-request accumulator. */
	private static final class Request {

		private final List<Frame> stack = new ArrayList<>();

		/**
		 * Synthetic root holding the page's top-level timed nodes as children.
		 * Identity-keyed lookup so each template PNode maps to exactly one TreeNode
		 * per phase, no matter how many times it renders.
		 */
		private final TreeNode root = new TreeNode( null, Phase.APPEND );

		// Keyed by IdentityKey (node-by-reference + phase). A regular HashMap is
		// correct here: IdentityKey.equals/hashCode encode node identity via ==,
		// so two keys for the same template position+phase collapse to one entry.
		private final Map<Object, TreeNode> nodesByIdentity = new HashMap<>();

		private long bindingPullNanos;
		private int bindingPullCount;
		private long bindingPushNanos;
		private int bindingPushCount;

		/**
		 * @return the (single) TreeNode for this template position+phase, creating
		 *         and linking it under its parent on first encounter.
		 */
		private TreeNode treeNodeFor( final PNode node, final Phase phase, final TreeNode parent ) {
			final TreeNode effectiveParent = parent == null ? root : parent;

			// Key by node identity + phase + parent tree node. Same template position
			// under the same parent (e.g. a repetition body rendered 240×) collapses
			// to one TreeNode; phase keeps take-values/append distinct; the parent
			// component keeps a reused component reference under one parent from
			// merging with the same reference reached via a different path.
			final Object key = new IdentityKey( node, phase, effectiveParent );
			final TreeNode existing = nodesByIdentity.get( key );
			if( existing != null ) {
				return existing;
			}

			final TreeNode created = new TreeNode( node, phase );
			nodesByIdentity.put( key, created );
			effectiveParent.children.add( created );
			return created;
		}

		private Result toResult() {
			return new Result( root, bindingPullNanos, bindingPullCount, bindingPushNanos, bindingPushCount );
		}
	}

	/**
	 * Identity-based composite key (PNode by reference + phase). IdentityHashMap
	 * compares keys with ==, so we make equals/hashCode reflect node identity.
	 */
	private static final class IdentityKey {

		private final PNode node;
		private final Phase phase;
		private final TreeNode parent;

		private IdentityKey( final PNode node, final Phase phase, final TreeNode parent ) {
			this.node = node;
			this.phase = phase;
			this.parent = parent;
		}

		@Override
		public boolean equals( final Object o ) {
			if( !(o instanceof IdentityKey other) ) {
				return false;
			}
			return node == other.node && phase == other.phase && parent == other.parent;
		}

		@Override
		public int hashCode() {
			int h = System.identityHashCode( node );
			h = h * 31 + phase.ordinal();
			h = h * 31 + System.identityHashCode( parent );
			return h;
		}
	}

	/** The finished profile for a request. */
	public static final class Result {

		private final TreeNode root;
		private final long bindingPullNanos;
		private final int bindingPullCount;
		private final long bindingPushNanos;
		private final int bindingPushCount;

		private Result( final TreeNode root, final long bindingPullNanos, final int bindingPullCount, final long bindingPushNanos, final int bindingPushCount ) {
			this.root = root;
			this.bindingPullNanos = bindingPullNanos;
			this.bindingPullCount = bindingPullCount;
			this.bindingPushNanos = bindingPushNanos;
			this.bindingPushCount = bindingPushCount;
		}

		/** @return the synthetic root; its children are the page's top-level timed nodes. */
		public TreeNode root() {
			return root;
		}

		public boolean isEmpty() {
			return root.children.isEmpty();
		}

		/** @return total inclusive time across top-level nodes (≈ whole-page render). */
		public long totalInclusiveNanos() {
			long total = 0;
			for( final TreeNode child : root.children ) {
				total += child.inclusiveNanos;
			}
			return total;
		}

		public long bindingPullNanos() {
			return bindingPullNanos;
		}

		public int bindingPullCount() {
			return bindingPullCount;
		}

		public long bindingPushNanos() {
			return bindingPushNanos;
		}

		public int bindingPushCount() {
			return bindingPushCount;
		}
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static String describe( final PNode node ) {
		if( node instanceof PBasicNode basic ) {
			final String ns = basic.namespace();
			final String type = basic.type();
			return ns == null ? type : ns + ":" + type;
		}
		return node == null ? "page" : node.getClass().getSimpleName();
	}

	/**
	 * @return the element's start character offset, or 0 if unknown. (A true line
	 *         number needs the template source, which we don't have here — the
	 *         exception-page code already resolves offset→line and that logic would
	 *         be reused for the real feature.) // prototype
	 */
	private static int offsetOf( final PNode node ) {
		if( node == null ) {
			return 0;
		}
		final SourceRange range = node.sourceRange();
		return range == null ? 0 : range.start();
	}
}
