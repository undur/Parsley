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
		return enterElement( node, phase, null, 0, null );
	}

	/**
	 * As {@link #enterElement(PNode, Phase)}, additionally carrying the component
	 * name, 1-based source line, and a short bindings summary so the heat map can
	 * build a click-to-open link and show orientation hints on each row.
	 */
	public static Frame enterElement( final PNode node, final Phase phase, final String componentName, final int line, final String bindingsSummary ) {
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
		final TreeNode treeNode = request.treeNodeFor( node, phase, parent == null ? null : parent.treeNode, componentName, line, bindingsSummary );

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

		// "Own work" = this frame's self-time minus the binding pulls that physically
		// happened in this frame. Those binding nanos are tracked separately (on the
		// owning element's bindingNanos) so that displayed self = ownWork + bind holds
		// with bind ⊆ self on every row — see TreeNode.selfNanos(). Clamped at 0 so a
		// frame whose entire self was binding work can't go negative.
		final long ownWork = Math.max( 0, self - frame.bindingNanosMeasuredHere );

		frame.treeNode.record( ownWork, inclusive );

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
		recordBindingPull( nanos, null );
	}

	/**
	 * @param owningNode the template node the binding belongs to (the element that
	 *        declared it), or null if unknown. Used to attribute the time to the
	 *        right row — see {@link #frameForOwningNode}.
	 */
	public static void recordBindingPull( final long nanos, final PNode owningNode ) {
		if( !_enabled ) {
			return;
		}
		final Request request = _current.get();
		if( request == null ) {
			return;
		}
		request.bindingPullNanos += nanos;
		request.bindingPullCount++;
		creditBinding( request, owningNode, nanos );
	}

	public static void recordBindingPush( final long nanos ) {
		recordBindingPush( nanos, null );
	}

	public static void recordBindingPush( final long nanos, final PNode owningNode ) {
		if( !_enabled ) {
			return;
		}
		final Request request = _current.get();
		if( request == null ) {
			return;
		}
		request.bindingPushNanos += nanos;
		request.bindingPushCount++;
		creditBinding( request, owningNode, nanos );
	}

	/**
	 * Attributes binding time to the element the binding actually belongs to.
	 *
	 * <p>A dynamic element evaluates its bindings inline during its own render, so it
	 * is on top of the stack — but a <em>component</em>'s bindings are pulled by WO
	 * machinery (pullValuesFromParent / lazy valueForBinding) while some inner element
	 * is on top, with the component-reference frame sitting <em>below</em> it. So we
	 * don't blindly credit the stack top: we walk down to the frame whose node is the
	 * binding's owning node and credit that. This is symmetric — a dynamic element
	 * matches itself immediately; a component binding matches its reference frame
	 * underneath the inner elements — and the live stack disambiguates repetitions
	 * (it finds the current iteration's frame). Falls back to the stack top if the
	 * owning node is unknown or not found on the stack.
	 */
	private static void creditBinding( final Request request, final PNode owningNode, final long nanos ) {
		if( request.stack.isEmpty() ) {
			return;
		}

		final Frame top = request.stack.get( request.stack.size() - 1 );
		final Frame owner = ownerFrame( request, owningNode );

		// Attribute the binding time to the element that DECLARES the binding (the
		// owning node), so the "bind" column is symmetric: a dynamic element and a
		// component both show the binding time for the bindings they declare, even
		// though WO pulls a component's bindings while an inner element is on the
		// stack. This is what keeps the column consistent regardless of element kind.
		owner.treeNode.bindingNanos += nanos;

		// Record, on the frame where the pull physically happened, how much of its
		// measured wall-clock was binding time. We subtract this later so a frame's
		// "own work" excludes binding pulls — see TreeNode.record / self computation.
		// This is what lets us define self = ownWork + bind with bind ⊆ self ALWAYS,
		// for both the owner (if different) and the running frame, without moving
		// nanos across frames.
		top.bindingNanosMeasuredHere += nanos;
	}

	/**
	 * @return the frame to credit: the nearest stack frame (top-down) whose node
	 *         equals {@code owningNode}, else the stack top.
	 */
	private static Frame ownerFrame( final Request request, final PNode owningNode ) {
		if( owningNode != null ) {
			for( int i = request.stack.size() - 1; i >= 0; i-- ) {
				if( request.stack.get( i ).treeNode.node == owningNode ) {
					return request.stack.get( i );
				}
			}
		}
		// Unknown owner, or owner not on the stack — fall back to the rendering element.
		return request.stack.get( request.stack.size() - 1 );
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

	/**
	 * @return whether {@code <body>} has been seen in this request's response yet.
	 *         A one-way latch so the marker-safety check doesn't rescan the growing
	 *         response string on every element. False when profiling is off.
	 */
	public static boolean bodyHasOpened() {
		final Request request = _current.get();
		return request != null && request.bodyOpened;
	}

	/** Latches "we are now inside &lt;body&gt;" for the current request. */
	public static void markBodyOpened() {
		final Request request = _current.get();
		if( request != null ) {
			request.bodyOpened = true;
		}
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

		/**
		 * Binding-pull nanos that physically elapsed while this frame was the running
		 * (top) frame — regardless of which element the pull is attributed to. On
		 * exit, subtracted from the frame's self-time to get "own work", so binding
		 * time isn't double-counted between a frame's own-work and the bind column.
		 */
		private long bindingNanosMeasuredHere;

		private Frame( final TreeNode treeNode, final long startNanos ) {
			this.treeNode = treeNode;
			this.startNanos = startNanos;
		}

		/**
		 * @return the stable id of this frame's template position, for emitting
		 *         {@code <!--parsley:ID-->} markers around the rendered output so
		 *         the heat map can locate and highlight the element in the page.
		 */
		public int positionId() {
			return treeNode.id;
		}
	}

	/**
	 * A node in the timed template tree. One per (template PNode + phase) position;
	 * accumulates time across every render of that position.
	 */
	public static final class TreeNode {

		/**
		 * Stable id for this template position within the request, used to correlate
		 * the heat-map row with {@code <!--parsley:ID-->} markers emitted around the
		 * element's rendered output. -1 for the synthetic root.
		 */
		private final int id;

		private final PNode node;
		private final Phase phase;
		private final String label;
		private final int offset;
		private final int length;
		private final String componentName;
		private final int line;
		private final String bindingsSummary;
		private final List<TreeNode> children = new ArrayList<>();

		/**
		 * Wall-clock spent in this element's own code, excluding descendants AND
		 * excluding binding pulls. Displayed self-time is {@code ownWorkNanos +
		 * bindingNanos}, which guarantees bind ⊆ self on every row regardless of
		 * whether the element is a dynamic element or a component.
		 */
		private long ownWorkNanos;
		private long inclusiveNanos;
		private long bindingNanos;
		private int count;

		private TreeNode( final int id, final PNode node, final Phase phase, final String componentName, final int line, final String bindingsSummary ) {
			this.id = id;
			this.node = node;
			this.phase = phase;
			this.label = describe( node );
			this.offset = offsetOf( node );
			this.length = lengthOf( node );
			this.componentName = componentName;
			this.line = line;
			this.bindingsSummary = bindingsSummary;
		}

		public int id() {
			return id;
		}

		private void record( final long ownWork, final long inclusive ) {
			ownWorkNanos += ownWork;
			inclusiveNanos += inclusive;
			count++;
		}

		public String label() {
			return label;
		}

		public int offset() {
			return offset;
		}

		public int length() {
			return length;
		}

		public String componentName() {
			return componentName;
		}

		public int line() {
			return line;
		}

		public String bindingsSummary() {
			return bindingsSummary;
		}

		public Phase phase() {
			return phase;
		}

		/**
		 * @return self-time: the element's own work plus the time spent resolving the
		 *         bindings it declares. Defined as {@code ownWorkNanos + bindingNanos},
		 *         so {@code bindingNanos() <= selfNanos()} always holds — the "self"
		 *         and "bind" columns are consistent on every row, component or not.
		 */
		public long selfNanos() {
			return ownWorkNanos + bindingNanos;
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
		private final TreeNode root = new TreeNode( -1, null, Phase.APPEND, null, 0, null );

		/** Monotonic id source for template positions within this request. */
		private int nextId = 0;

		/** One-way latch: set once {@code <body>} appears in the response. */
		private boolean bodyOpened = false;

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
		private TreeNode treeNodeFor( final PNode node, final Phase phase, final TreeNode parent, final String componentName, final int line, final String bindingsSummary ) {
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

			final TreeNode created = new TreeNode( nextId++, node, phase, componentName, line, bindingsSummary );
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

	/**
	 * @return the element's source span length (end - start), or 0 if unknown. Used
	 *         to select the element's whole tag when opening it in the IDE.
	 */
	private static int lengthOf( final PNode node ) {
		if( node == null ) {
			return 0;
		}
		final SourceRange range = node.sourceRange();
		return range == null ? 0 : Math.max( 0, range.end() - range.start() );
	}
}
