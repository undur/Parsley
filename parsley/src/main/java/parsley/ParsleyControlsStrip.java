package parsley;

import com.webobjects.appserver.WOApplication;

/**
 * Renders the development controls strip — a small button anchored in the page's
 * bottom-left corner that expands (à la the classic Mac OS Control Strip) to reveal
 * buttons for toggling Parsley's dev features at runtime. Injected into the response by
 * {@link ParsleyRequestObserver} when {@link Parsley#showControls()} is on.
 *
 * <p>Self-contained HTML/CSS/JS, no external dependencies. Each toggle is a link to
 * {@link ParsleyControlsAction}; the expand/collapse is local state.
 */
final class ParsleyControlsStrip {

	private static final int BUTTON_SIZE_PX = 36;

	private ParsleyControlsStrip() {}

	/**
	 * @return the strip markup to inject before {@code </body>}, or an empty string if
	 *         the controls direct-action URL can't be built (no running application).
	 */
	static String render( final boolean inlineErrorsOn ) {
		final String actionBase = controlsActionBaseURL();
		if( actionBase == null ) {
			return "";
		}

		return """
				<div id="parsleyControls" style="position:fixed;bottom:0;left:0;z-index:2147483647;font-family:ui-sans-serif,system-ui,sans-serif;font-size:13px">
				  <button id="parsleyControlsToggle" onclick="parsleyControlsExpand(event)" title="Parsley controls"
				    style="all:unset;cursor:pointer;display:block;width:%spx;height:%spx;line-height:%spx;text-align:center;font-size:18px;background:#2d2f36;color:#9ecb6f;border-top-right-radius:6px;box-shadow:0 0 4px rgba(0,0,0,0.4)">%s</button>
				  <div id="parsleyControlsPanel" style="display:none;background:#2d2f36;color:#e6e6e6;padding:4px;border-top-right-radius:6px;box-shadow:0 -1px 6px rgba(0,0,0,0.4)">
				    <div style="padding:2px 6px;color:#9ca3af;font-size:10px;text-transform:uppercase;letter-spacing:0.05em">Parsley</div>
				    %s
				    %s
				  </div>
				</div>
				<script>
				  function parsleyControlsSet(open){
				    document.getElementById('parsleyControlsPanel').style.display=open?'block':'none';
				    document.getElementById('parsleyControlsToggle').style.display=open?'none':'block';
				  }
				  function parsleyControlsExpand(e){
				    if(e){ e.stopPropagation(); }
				    parsleyControlsSet( document.getElementById('parsleyControlsPanel').style.display==='none' );
				  }
				  // Clicking anywhere outside the controls collapses them back to the button.
				  document.addEventListener('click', function(e){
				    if(!document.getElementById('parsleyControls').contains(e.target)){ parsleyControlsSet(false); }
				  });
				</script>
				""".formatted(
				BUTTON_SIZE_PX, BUTTON_SIZE_PX, BUTTON_SIZE_PX,
				ParsleyConstants.HERB,
				button( actionBase + "toggleInlineErrors", (inlineErrorsOn ? "✓ " : "") + "Inline errors" ),
				button( actionBase + "hideControls", "Hide controls" ) );
	}

	/**
	 * @return the full direct-action URL prefix for the controls actions —
	 *         {@code /Apps/WebObjects/<App>.woa/wa/ParsleyControlsAction/} — built
	 *         server-side from the running application so it's correct regardless of how
	 *         the current page was routed. The WO direct-action URL uses the full class
	 *         name; only the action method drops its {@code Action} suffix. Null if there's
	 *         no running application.
	 */
	private static String controlsActionBaseURL() {
		try {
			final WOApplication application = WOApplication.application();
			if( application == null ) {
				return null;
			}
			return application.applicationBaseURL() + "/" + application.name() + ".woa/wa/ParsleyControlsAction/";
		}
		catch( final Throwable t ) {
			return null;
		}
	}

	/**
	 * @return one strip button, a link to a controls action.
	 */
	private static String button( final String url, final String label ) {
		return """
				<a href="%s" style="display:block;padding:6px 12px;color:#e6e6e6;text-decoration:none;border-radius:3px;white-space:nowrap"
				  onmouseover="this.style.background='#3b3e47'" onmouseout="this.style.background='transparent'">%s</a>
				""".formatted( url, label );
	}
}
