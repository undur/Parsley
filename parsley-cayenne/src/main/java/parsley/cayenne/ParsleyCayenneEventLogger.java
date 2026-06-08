package parsley.cayenne;

import org.apache.cayenne.access.translator.ParameterBinding;
import org.apache.cayenne.configuration.RuntimeProperties;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.log.Slf4jJdbcEventLogger;

import parsley.ParsleyRenderProfiler;

/**
 * A Cayenne {@link org.apache.cayenne.log.JdbcEventLogger} that feeds query timing
 * into Parsley's render profiler, so the render heat map can attribute database time
 * (and, crucially, query <em>count</em> — the N+1 signal) to the exact template
 * position that triggered each fetch.
 *
 * <p>This is the persistence-side adapter in the split that keeps Parsley core
 * framework-agnostic: <b>core knows nothing about Cayenne</b> and merely exposes the
 * neutral static sink {@link ParsleyRenderProfiler#recordQuery(long, String)}; this
 * class is the only thing that knows about JDBC/Cayenne, and it just translates
 * Cayenne's logger callbacks into that one call. An EOF (or any other) adapter would
 * be a sibling of this class calling the same sink — see {@code parsley-eof} (future).
 *
 * <h2>Why we pair two callbacks</h2>
 *
 * Cayenne reports the SQL and the timing through <em>separate</em> methods:
 * {@link #logQuery} carries the statement (with bound parameters), while
 * {@link #logSelectCount} carries the elapsed time. Both fire synchronously, on the
 * same thread, between the {@code valueForKey} that triggered the fetch — so when the
 * timing arrives, the render stack still points at the element that caused it, and the
 * SQL we stashed from {@code logQuery} is the matching statement. We keep that last SQL
 * in a {@link ThreadLocal} and hand it to the profiler alongside the timing.
 *
 * <h2>Cost</h2>
 *
 * Every hook is gated on {@link ParsleyRenderProfiler#isEnabled()}, so when profiling
 * is off (production) this logger behaves exactly like its superclass with only a
 * volatile read of overhead. Install it via {@link ParsleyCayenne}.
 */
public class ParsleyCayenneEventLogger extends Slf4jJdbcEventLogger {

	/**
	 * The SQL most recently passed to {@link #logQuery} on this thread, used to label
	 * the timing that arrives moments later via {@link #logSelectCount}. Per-thread
	 * because Cayenne runs each request's fetches on its own thread.
	 */
	private final ThreadLocal<String> _lastSql = new ThreadLocal<>();

	public ParsleyCayenneEventLogger( @Inject RuntimeProperties runtimeProperties ) {
		super( runtimeProperties );
	}

	@Override
	public void logQuery( final String sql, final ParameterBinding[] bindings ) {
		if( ParsleyRenderProfiler.isEnabled() ) {
			_lastSql.set( sql );
		}
		super.logQuery( sql, bindings );
	}

	@Override
	public void logSelectCount( final int count, final long timeMs, final String sql ) {
		recordIfProfiling( timeMs, sql );
		super.logSelectCount( count, timeMs, sql );
	}

	@Override
	public void logSelectCount( final int count, final long timeMs ) {
		recordIfProfiling( timeMs, null );
		super.logSelectCount( count, timeMs );
	}

	/**
	 * Routes a completed query's wall-clock to the profiler, preferring the SQL passed
	 * with the timing and falling back to the statement we stashed in {@link #logQuery}.
	 *
	 * @param timeMs the query's elapsed time in milliseconds, as Cayenne reports it
	 * @param sql    the SQL Cayenne passed with the timing, or null
	 */
	private void recordIfProfiling( final long timeMs, final String sql ) {
		if( ParsleyRenderProfiler.isEnabled() ) {
			final String effectiveSql = sql != null ? sql : _lastSql.get();
			ParsleyRenderProfiler.recordQuery( timeMs * 1_000_000L, effectiveSql );
			_lastSql.remove();
		}
	}
}
