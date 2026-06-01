package parsley;

import java.util.ArrayList;
import java.util.List;

import com.webobjects.appserver.WOResponse;
import com.webobjects.foundation.NSNotification;

public class ParsleyRequestObserver {

	/**
	 * FIXME: We probably shouldn't be using a ThreadLocal, seing as how WorkerThreads are reused in WO. It will work fine though, since the variable gets reset at the end of the request // Hugi 2025-03-26
	 */
	public static ThreadLocal<List<String>> errors = ThreadLocal.withInitial( ArrayList::new );

	public void didHandleRequest( NSNotification notification ) {

		final WOResponse response = (WOResponse)notification.object();

		if( !errors.get().isEmpty() ) {
			final int errorCount = errors.get().size();

			String errorDiv = """
					<div style="width: 100%%; height: 100px; position: fixed; top: 0px; right: 0px; background-color: rgba(255,0,0,0.6); border-bottom: 2px solid red; color: white; padding: 32px; text-align: center; text-shadow: 1px 1px 2px black; pointer-events: none; z-index: 2147483647">
						<h2 style="font-size: 24px">%s %s %s on page</h2>
					</div>
					<div style="width: 100%%; height: 100%%; position: fixed; top: 100px; right: 0px; background-color: rgba(255,140,0,0.1); pointer-events: none; z-index: 2147483646; border: 0px solid red">
						&nbsp;
					</div>
					""".formatted( ParsleyConstants.HERB, errorCount, (errorCount == 1 ? "error" : "errors") );

			response.setContent( response.contentString().replace( "</body>", errorDiv + "</body>" ) );

			errors.set( new ArrayList<>() );
		}

		// PROTOTYPE — render the render-time heat map overlay, if profiling is on.
		if( ParsleyRenderProfiler.isEnabled() ) {
			try {
				final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
				if( result != null && !result.isEmpty() ) {
					final String content = response.contentString();
					if( content != null && content.contains( "</body>" ) ) {
						final String overlay = ParsleyRenderHeatmapOverlay.render( result );
						response.setContent( content.replace( "</body>", overlay + "</body>" ) );
					}
				}
			}
			finally {
				// Always reset, even if rendering the overlay failed, so the next
				// request on this reused worker thread starts clean.
				ParsleyRenderProfiler.reset();
			}
		}
	}
}
