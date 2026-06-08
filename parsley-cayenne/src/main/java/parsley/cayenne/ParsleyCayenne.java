package parsley.cayenne;

import org.apache.cayenne.di.Module;
import org.apache.cayenne.log.JdbcEventLogger;

/**
 * Entry point for enabling Parsley's database-time profiling on a Cayenne runtime.
 *
 * <p>Cayenne resolves its {@link JdbcEventLogger} through dependency injection, so to
 * have queries reported to Parsley's render profiler you contribute a module that
 * binds {@link ParsleyCayenneEventLogger} in place of the default logger when building
 * your {@code ServerRuntime}:
 *
 * <pre>{@code
 * ServerRuntime runtime = ServerRuntime.builder()
 *     .addConfig("cayenne-project.xml")
 *     .addModule(ParsleyCayenne.profilingModule())
 *     .build();
 * }</pre>
 *
 * <p>This mirrors how the optional {@code parsley-ognl} module opts in via a single
 * registration call: Parsley core stays unaware of Cayenne, and an app pulls in this
 * module only if it wants Cayenne query timing in the heat map. The bound logger is a
 * no-op (beyond delegating to its superclass) whenever
 * {@link parsley.ParsleyRenderProfiler#isEnabled()} is false, so it's safe to leave the
 * module installed in production — profiling is gated by the same master switch as the
 * rest of the heat map.
 */
public final class ParsleyCayenne {

	private ParsleyCayenne() {}

	/**
	 * @return a Cayenne DI module that binds {@link ParsleyCayenneEventLogger} as the
	 *         runtime's {@link JdbcEventLogger}. Add it to your {@code ServerRuntime}
	 *         via {@code .addModule(...)}. Registering it last ensures it wins over
	 *         Cayenne's default logger binding.
	 */
	public static Module profilingModule() {
		return binder -> binder
				.bind( JdbcEventLogger.class )
				.to( ParsleyCayenneEventLogger.class )
				.inSingletonScope();
	}
}
