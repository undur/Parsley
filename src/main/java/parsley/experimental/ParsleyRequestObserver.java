package parsley.experimental;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.webobjects.appserver.WOResponse;
import com.webobjects.foundation.NSNotification;

public class ParsleyRequestObserver {

	public ThreadLocal<List<String>> errors = ThreadLocal.withInitial( ArrayList::new );
	AtomicInteger i = new AtomicInteger();

	public void didHandleRequest( NSNotification notification ) {
		if( !errors.get().isEmpty() ) {
			String errorDiv = """
					<div style="width: 1000px; height: 400px; position: absolute; top: 0px; right: 0px; background-color: rgba(255,0,0,0.8); border: 2px solid red; color: white; overflow: scroll; padding: 10px">
						<h2>Errors</h2>
						%s
					</div>
					""".formatted( errors.get().toString() );

			System.out.println( errors.get() );
			final WOResponse response = (WOResponse)notification.object();
			response.setContent( response.contentString().replace( "</html>", errorDiv + "</html>" ) );
			System.out.println( i.incrementAndGet() + " response: " + response.contentString() );
		}
	}
}