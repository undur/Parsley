package parsley;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WODirectAction;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

/**
 * Direct action backing the development controls strip (see {@link ParsleyControlsStrip}).
 * Reached at {@code …/wa/ParsleyControls/<action>}, each action toggles a Parsley dev
 * feature at runtime and returns a tiny confirmation page with a link back to the app —
 * the user navigates back (or uses the back button) to a freshly-rendered page that
 * reflects the new state.
 *
 * <p>This is intentionally minimal; it's the foothold for more client-side dev controls
 * over time.
 */
public class ParsleyControlsAction extends WODirectAction {

	private static final Logger logger = LoggerFactory.getLogger( ParsleyControlsAction.class );

	public ParsleyControlsAction( final WORequest request ) {
		super( request );
	}

	/**
	 * Toggles inline error rendering on/off. No-ops when the controls strip is disabled,
	 * so a stray URL can't flip features when controls aren't enabled (notably in
	 * production).
	 */
	public WOActionResults toggleInlineErrorsAction() {
		if( !toggleInlineErrors() ) {
			return controlsDisabledResponse();
		}
		return confirmation( "Inline error rendering " + onOff( Parsley.showInlineErrorMessages() ) );
	}

	/**
	 * Toggles the render heat map on/off. No-ops when the controls strip is disabled.
	 */
	public WOActionResults toggleRenderProfilerAction() {
		if( !toggleRenderProfiler() ) {
			return controlsDisabledResponse();
		}
		return confirmation( "Render heat map " + onOff( Parsley.showRenderProfiler() ) );
	}

	/**
	 * Hides the controls strip for the rest of the session (until re-enabled in config).
	 * No-ops when the controls strip is already disabled.
	 */
	public WOActionResults hideControlsAction() {
		if( !hideControls() ) {
			return controlsDisabledResponse();
		}
		return confirmation( "Parsley controls hidden" );
	}

	/**
	 * Flips inline error rendering, but only while the controls strip is enabled.
	 * Re-registers and flushes the template cache so the change takes effect on every
	 * page (wrapping is decided at parse time, so already-parsed templates re-parse).
	 *
	 * @return true if the toggle was applied, false (no-op) if controls are disabled.
	 */
	static boolean toggleInlineErrors() {
		if( !Parsley.showControls() ) {
			return false;
		}
		Parsley.configure().inlineErrors( !Parsley.showInlineErrorMessages() ).register();
		flushTemplateCache();
		return true;
	}

	/**
	 * Flips the render heat map, but only while the controls strip is enabled. Like the
	 * inline-errors toggle, re-registers and flushes the template cache so wrapping takes
	 * effect on every page (the profiler needs elements wrapped, decided at parse time).
	 *
	 * @return true if the toggle was applied, false (no-op) if controls are disabled.
	 */
	static boolean toggleRenderProfiler() {
		if( !Parsley.showControls() ) {
			return false;
		}
		Parsley.configure().renderProfiler( !Parsley.showRenderProfiler() ).register();
		flushTemplateCache();
		return true;
	}

	/**
	 * Hides the controls strip, but only while it's enabled.
	 *
	 * @return true if hidden, false (no-op) if controls were already off.
	 */
	static boolean hideControls() {
		if( !Parsley.showControls() ) {
			return false;
		}
		Parsley.configure().controls( false ).register();
		return true;
	}

	/**
	 * Forces WO to re-parse templates on next access by clearing its component
	 * definition cache.
	 */
	private static void flushTemplateCache() {
		try {
			final WOApplication application = WOApplication.application();
			if( application != null ) {
				application._removeComponentDefinitionCacheContents();
			}
		}
		catch( final Throwable t ) {
			// No running WOApplication (e.g. unit tests) — nothing to flush.
			logger.debug( "Skipped template cache flush; no running application", t );
		}
	}

	private WOActionResults controlsDisabledResponse() {
		final WOResponse response = new WOResponse();
		response.setStatus( 404 );
		response.setHeader( "text/plain; charset=utf-8", "content-type" );
		response.setContent( "Parsley controls are not enabled." );
		return response;
	}

	/**
	 * @return a minimal confirmation response: the message and a link back to the app's
	 *         front page. Deliberately bare — the user navigates back themselves.
	 */
	private WOActionResults confirmation( final String message ) {
		final WOResponse response = new WOResponse();
		response.setHeader( "text/html; charset=utf-8", "content-type" );
		response.setContent( """
				<!DOCTYPE html>
				<html><head><title>Parsley</title></head>
				<body style="font-family:ui-sans-serif,system-ui,sans-serif;padding:40px;color:#222">
					<p style="font-size:18px">%s %s</p>
					<p><a href="/" style="color:#3b82f6">← back to the app</a></p>
				</body></html>
				""".formatted( ParsleyConstants.HERB, message ) );
		return response;
	}

	private static String onOff( final boolean value ) {
		return value ? "enabled" : "disabled";
	}
}
