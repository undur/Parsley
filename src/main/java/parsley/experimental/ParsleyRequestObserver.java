package parsley.experimental;

import java.util.ArrayList;
import java.util.List;

import com.webobjects.appserver.WOResponse;
import com.webobjects.foundation.NSNotification;

public class ParsleyRequestObserver {

	/**
	 * FIXME: We shouldn't be using a threadlocal. Since WorkerThreads are reused in WO this won't cut it // Hugi 2025
	 */
	public static ThreadLocal<List<String>> errors = ThreadLocal.withInitial( ArrayList::new );

	public void didHandleRequest( NSNotification notification ) {
		if( !errors.get().isEmpty() ) {
			String errorDiv = """
					<div style="width: 100%%; height: 100px; position: absolute; top: 0px; right: 0px; background-color: rgba(255,0,0,0.6); border-bottom: 2px solid red; color: white; padding: 32px; text-align: center; text-shadow: 1px 1px 2px black">
						<h2 style="font-size: 24px">%s %s errors on page</h2>
					</div>
					""".formatted( ParsleyConstants.HERB, String.valueOf( errors.get().size() ) );
			//			String errorDiv = """
			//					<div style="width: 1000px; height: 400px; position: absolute; top: 0px; right: 0px; background-color: rgba(255,0,0,0.8); border: 2px solid red; color: white; overflow: scroll; padding: 10px">
			//						<h2>Errors</h2>
			//						%s
			//					</div>
			//					""".formatted( errors.get().toString() );

			final WOResponse response = (WOResponse)notification.object();
			response.setContent( response.contentString().replace( "</html>", errorDiv + "</html>" ) );

			errors.set( new ArrayList<>() );
		}
	}
}