package parsley;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ng.appserver.templating.parser.model.PBasicNode;
import ng.appserver.templating.parser.model.PNode;
import ng.appserver.templating.parser.model.SourceRange;

/**
 * PROTOTYPE — a per-request render profiler that measures how long each template
 * element takes to render, broken down into <em>self-time</em> (time spent in the
 * element itself, excluding its descendants) and tracks total time spent pulling
 * and pushing bindings.
 *
 * <p>The data is gathered by {@link ParsleyProxyElement} (which already wraps every
 * element and intercepts the three request phases) and by
 * {@link ParsleyKeyValueAssociation} (binding read/write), then rendered as an
 * inline "heat map" overlay by {@link ParsleyRequestObserver} once the response is
 * complete.
 *
 * <h2>Why self-time, not inclusive-time</h2>
 *
 * If we simply timed each proxy's {@code appendToResponse} from entry to exit, a
 * container element (or the page root) would always dominate, because its time
 * includes every descendant's time. The heat map would just highlight the tree
 * spine and be useless. So we keep a small stack: when an element finishes, we know
 * its <em>inclusive</em> elapsed time, and we subtract the inclusive time of its
 * direct children (accumulated into the current frame as they pop) to get the
 * parent's self-time. Each element is therefore credited only with the work it did
 * directly — string building, binding evaluation, its own logic — not its children's.
 *
 * <h2>Threading</h2>
 *
 * Like {@link ParsleyRequestObserver}, state is held per-thread and reset at the end
 * of each request. WO reuses worker threads, so the reset is what keeps requests
 * from bleeding into each other.
 *
 * <p>This is a prototype: the accounting is real, but the storage model
 * (line-keyed aggregation), the overlay UI, and the "is this on" flag are all
 * deliberately simple so the feature can be <em>felt</em> before it's designed
 * properly. // 2026-06-01
 */
public final class ParsleyRenderProfiler {

	/**
	 * Master switch. When false, every hook is a cheap no-op (a single volatile
	 * read + branch) so a project that doesn't want profiling pays almost nothing.
	 */
	private static volatile boolean _enabled = false;

	/**
	 * Per-request, per-thread profiling state. Null when profiling is disabled or
	 * before the first element of a request is entered.
	 */
	private static final ThreadLocal<Request> _current = new ThreadLocal<>();

	private ParsleyRenderProfiler() {
		// Static facade only.
	}

	/**
	 * Enables/disables render profiling globally. Separate from inline error
	 * messages on purpose: a project may want one without the other, and profiling
	 * adds per-element timing overhead that error wrapping does not.
	 */
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
	 * Marks entry into an element's render phase. Returns a frame token that must
	 * be passed back to {@link #exitElement(Frame)} in a finally block. Returns
	 * null when profiling is off — callers treat null as "do nothing".
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

		final Frame frame = new Frame( node, phase, System.nanoTime() );
		request.stack.add( frame );
		return frame;
	}

	/**
	 * Marks exit from an element's render phase, recording its self-time. Safe to
	 * call with a null frame (when profiling was off at entry).
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

		// Self-time = inclusive time minus everything our children reported as
		// their own inclusive time (which they accumulated into us as they popped).
		final long self = inclusive - frame.childInclusiveNanos;

		request.record( frame.node, frame.phase, self );

		// Pop and hand our inclusive time up to our parent so the parent can
		// exclude us from its self-time.
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

	/**
	 * Records nanos spent reading a binding (valueInComponent).
	 */
	public static void recordBindingPull( final long nanos ) {
		if( !_enabled ) {
			return;
		}
		final Request request = _current.get();
		if( request != null ) {
			request.bindingPullNanos += nanos;
			request.bindingPullCount++;
		}
	}

	/**
	 * Records nanos spent writing a binding (setValue).
	 */
	public static void recordBindingPush( final long nanos ) {
		if( !_enabled ) {
			return;
		}
		final Request request = _current.get();
		if( request != null ) {
			request.bindingPushNanos += nanos;
			request.bindingPushCount++;
		}
	}

	// =========================================================================
	// Result access + reset (called by ParsleyRequestObserver)
	// =========================================================================

	/**
	 * @return the profiling result for the current request, or null if nothing was
	 *         measured (profiling off, or a request with no wrapped elements).
	 */
	public static Result takeResult() {
		final Request request = _current.get();
		if( request == null ) {
			return null;
		}
		return request.toResult();
	}

	/**
	 * Clears the current thread's profiling state. Must be called at end of request.
	 */
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

	/**
	 * A live stack frame for one element's render phase. Mutable: accumulates the
	 * inclusive time of its children so its own self-time can be computed on exit.
	 */
	public static final class Frame {

		private final PNode node;
		private final Phase phase;
		private final long startNanos;
		private long childInclusiveNanos;

		private Frame( final PNode node, final Phase phase, final long startNanos ) {
			this.node = node;
			this.phase = phase;
			this.startNanos = startNanos;
		}
	}

	/**
	 * Per-request accumulator. Aggregates self-time by template source line +
	 * element identity, so a hot element inside a repetition shows up as one hot
	 * line (summed across its occurrences) rather than hundreds of cold ones.
	 */
	private static final class Request {

		private final List<Frame> stack = new ArrayList<>();
		private final Map<String, Row> rowsByKey = new HashMap<>();

		private long bindingPullNanos;
		private int bindingPullCount;
		private long bindingPushNanos;
		private int bindingPushCount;

		private void record( final PNode node, final Phase phase, final long selfNanos ) {
			final String label = describe( node );
			final int line = lineOf( node );
			final String key = line + "|" + label + "|" + phase.name();

			Row row = rowsByKey.get( key );
			if( row == null ) {
				row = new Row( label, line, phase );
				rowsByKey.put( key, row );
			}
			row.add( selfNanos );
		}

		private Result toResult() {
			final List<Row> rows = new ArrayList<>( rowsByKey.values() );
			// Hottest first.
			rows.sort( ( a, b ) -> Long.compare( b.totalNanos, a.totalNanos ) );
			return new Result( rows, bindingPullNanos, bindingPullCount, bindingPushNanos, bindingPushCount );
		}
	}

	/**
	 * One aggregated heat-map row: a single element identity on a single source
	 * line in a single phase, with summed self-time across all its occurrences.
	 */
	public static final class Row {

		private final String label;
		private final int line;
		private final Phase phase;
		private long totalNanos;
		private long maxNanos;
		private int count;

		private Row( final String label, final int line, final Phase phase ) {
			this.label = label;
			this.line = line;
			this.phase = phase;
		}

		private void add( final long nanos ) {
			totalNanos += nanos;
			if( nanos > maxNanos ) {
				maxNanos = nanos;
			}
			count++;
		}

		public String label() {
			return label;
		}

		public int line() {
			return line;
		}

		public Phase phase() {
			return phase;
		}

		public long totalNanos() {
			return totalNanos;
		}

		public long maxNanos() {
			return maxNanos;
		}

		public int count() {
			return count;
		}
	}

	/**
	 * The finished profile for a request.
	 */
	public static final class Result {

		private final List<Row> rows;
		private final long bindingPullNanos;
		private final int bindingPullCount;
		private final long bindingPushNanos;
		private final int bindingPushCount;

		private Result( final List<Row> rows, final long bindingPullNanos, final int bindingPullCount, final long bindingPushNanos, final int bindingPushCount ) {
			this.rows = rows;
			this.bindingPullNanos = bindingPullNanos;
			this.bindingPullCount = bindingPullCount;
			this.bindingPushNanos = bindingPushNanos;
			this.bindingPushCount = bindingPushCount;
		}

		public List<Row> rows() {
			return rows;
		}

		public boolean isEmpty() {
			return rows.isEmpty();
		}

		public long totalSelfNanos() {
			long total = 0;
			for( final Row row : rows ) {
				total += row.totalNanos;
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

	/**
	 * @return a human label for the element, e.g. "wo:WOString" or "wo:if".
	 */
	private static String describe( final PNode node ) {
		if( node instanceof PBasicNode basic ) {
			final String ns = basic.namespace();
			final String type = basic.type();
			return ns == null ? type : ns + ":" + type;
		}
		return node == null ? "?" : node.getClass().getSimpleName();
	}

	/**
	 * @return the 1-based source line of the element's start, or 0 if unknown.
	 *
	 *         We don't have the template string here, so we approximate "line" as
	 *         the start character offset for now — the overlay shows it labeled as
	 *         an offset. Resolving to a true line number needs the template source,
	 *         which is a refinement for the real feature. // prototype
	 */
	private static int lineOf( final PNode node ) {
		if( node == null ) {
			return 0;
		}
		final SourceRange range = node.sourceRange();
		return range == null ? 0 : range.start();
	}
}
