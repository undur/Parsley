package parsley.cayenne;

import java.util.List;
import java.util.Map;

import org.apache.cayenne.access.translator.TranslatedStatement;
import org.apache.cayenne.configuration.RuntimeProperties;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.log.Slf4jSQLLogger;

import parsley.ParsleyRenderProfiler;

/**
 * A Cayenne {@link org.apache.cayenne.log.SQLLogger} that feeds query timing into
 * Parsley's render profiler, so the render heat map can attribute database time (and,
 * crucially, query <em>count</em> — the N+1 signal) to the exact template position that
 * triggered each fetch.
 *
 * <p>This is the persistence-side adapter in the split that keeps Parsley core
 * framework-agnostic: <b>core knows nothing about Cayenne</b> and merely exposes the
 * neutral static sink {@link ParsleyRenderProfiler#recordQuery(long, String)}; this class
 * is the only thing that knows about JDBC/Cayenne, and it just translates Cayenne's logger
 * callbacks into that one call. An EOF (or any other) adapter would be a sibling of this
 * class calling the same sink — see {@code parsley-eof} (future).
 *
 * <h2>How queries are captured</h2>
 *
 * As of Cayenne 5.0-M3 the {@link org.apache.cayenne.log.SQLLogger} API reports a
 * completed statement in a single callback — {@link #logSelect} (and {@link #logUpdate})
 * carry the SQL, the row count, and the elapsed time together. So, unlike the old
 * {@code JdbcEventLogger} design, there is no need to pair two callbacks or stash the SQL
 * on a {@link ThreadLocal}: we record straight from the one call.
 *
 * <h2>Timing precision</h2>
 *
 * The elapsed time Cayenne hands us is in whole <em>milliseconds</em> (it measures nanos
 * internally but divides before calling the logger, and the M3 interface has no
 * before-query hook we could use to time the span ourselves). So a sub-millisecond query
 * is attributed 0ms. The query <em>count</em> — the primary N+1 signal — remains exact;
 * only fine-grained per-query time resolution is lost. We convert ms → ns for the
 * profiler, whose sink is nanosecond-based.
 *
 * <h2>Cost</h2>
 *
 * Recording is gated on {@link ParsleyRenderProfiler#isEnabled()}, so when profiling is
 * off (production) this logger adds only a volatile read over its superclass. Note we
 * record independently of the SQL log level, so the heat map works even when
 * {@code cayenne-sql} logging is disabled. Install it via {@link ParsleyCayenne}.
 */
public class ParsleyCayenneEventLogger extends Slf4jSQLLogger {

	public ParsleyCayenneEventLogger( @Inject RuntimeProperties runtimeProperties ) {
		super( runtimeProperties );
	}

	@Override
	public void logSelect( final TranslatedStatement statement, final int rowCount, final long durationMillis ) {
		record( statement, durationMillis );
		super.logSelect( statement, rowCount, durationMillis );
	}

	@Override
	public void logUpdate( final TranslatedStatement statement, final int rowCount, final List<? extends Map<String, ?>> generatedKeys, final long durationMillis ) {
		record( statement, durationMillis );
		super.logUpdate( statement, rowCount, generatedKeys, durationMillis );
	}

	/**
	 * Routes a completed statement's SQL and elapsed time to the profiler, which attributes
	 * them to the template position currently rendering. No-op when profiling is off.
	 */
	private static void record( final TranslatedStatement statement, final long durationMillis ) {
		if( ParsleyRenderProfiler.isEnabled() ) {
			ParsleyRenderProfiler.recordQuery( durationMillis * 1_000_000L, statement == null ? null : statement.sql() );
		}
	}
}
