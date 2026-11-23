package de.bookmarksplus.enrich

/**
 * Hide cookie/consent banners – in three stages:
 * 1. do not load scripts of known consent managers at all (request is aborted),
 * 2. hide known banner containers via CSS,
 * 3. heuristic before the screenshot: remove fixed overlays with cookie/consent text and release scroll locks.
 */
object CookieBanners {

    /** Hosts of consent management platforms. */
    val BLOCKED_HOSTS = listOf(
        "cookiebot.com", "cookielaw.org", "onetrust.com", "consensu.org", "quantcast.com", "usercentrics.eu",
        "usercentrics.com", "didomi.io", "privacy-mgmt.com", "sp-prod.net", "sourcepoint.com", "trustarc.com",
        "truste.com", "consentmanager.net", "consentmanager.de", "iubenda.com", "termly.io", "cookieyes.com",
        "cookie-script.com", "osano.com", "cookiefirst.com", "cookiehub.net", "cookiepro.com", "uniconsent.com",
        "fundingchoicesmessages.google.com", "consent.cookiefirst.com", "cdn-cookieyes.com", "clickio.com",
        "axeptio.eu", "cmp.inmobi.com", "cmp.osano.com", "civiccomputing.com", "ensighten.com", "evidon.com",
        "borlabs.io", "consent.trustarc.com", "cookieinformation.com", "cookie-cdn.cookiepro.com", "privacy-center.org",
    )

    fun isBlocked(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private val SELECTORS = listOf(
        "#onetrust-consent-sdk", "#onetrust-banner-sdk", "#CybotCookiebotDialog", "#CybotCookiebotDialogBodyUnderlay",
        "#usercentrics-root", "#usercentrics-cmp-ui", "#didomi-host", ".didomi-popup-open", "#qc-cmp2-container", ".qc-cmp2-container",
        "[id^=sp_message_container]", ".sp_veil", "#cmpbox", "#cmpbox2", ".cmpboxBG", "#cookie-law-info-bar", ".cli-modal-backdrop",
        ".cc-window", ".cc-banner", ".cookie-notice", "#cookie-notice", "#cookie-banner", ".cookie-banner", "#cookiebanner",
        ".cookiebanner", "#cookie-consent", ".cookie-consent", "#cookieConsent", ".cookieConsent", ".js-cookie-consent",
        "#gdpr-cookie-message", ".gdpr-banner", "#gdpr-banner", ".fc-consent-root", ".truste_box_overlay", "#truste-consent-track",
        "#BorlabsCookieBox", "#BorlabsCookieBoxWrap", ".borlabs-cookie", "#cookiescript_injected", "#cookieyes-banner",
        ".cky-consent-container", ".cky-overlay", "#termly-code-snippet-support", ".osano-cm-window", "#iubenda-cs-banner",
        "#cookiefirst-root", ".cookiefirst-root", "#axeptio_overlay", ".klaro", "#klaro", "#moove_gdpr_cookie_info_bar",
        "#cmplz-cookiebanner-container", ".cmplz-cookiebanner", "#hs-eu-cookie-confirmation", "#ccc", "#ccc-overlay",
        ".consent-banner", "#consent-banner", "[aria-label='cookieconsent']", "[aria-describedby='cookieconsent:desc']",
        "#tarteaucitronRoot", ".evidon-banner", "#_evidon_banner", "#cookie-information-template-wrapper",
        "ytd-consent-bump-v2-lightbox", "tp-yt-paper-dialog.ytd-consent-bump-v2-lightbox",
    )

    /** Injected before every document. */
    val INIT_SCRIPT: String = """
        (() => {
          const css = `${SELECTORS.joinToString(",")} { display: none !important; visibility: hidden !important; }`;
          const add = () => {
            if (document.getElementById('__bmp_hide')) return;
            const s = document.createElement('style'); s.id = '__bmp_hide'; s.textContent = css;
            (document.head || document.documentElement).appendChild(s);
          };
          if (document.documentElement) add(); else document.addEventListener('DOMContentLoaded', add);
        })();
    """.trimIndent()

    /** Executed immediately before the screenshot. */
    val CLEANUP_SCRIPT: String = """
        (() => {
          const rx = /(cookie|consent|gdpr|dsgvo|datenschutz|privacy|zustimm|einwillig|tracking|akzeptier|accept all|alle akzeptieren)/i;
          const vw = window.innerWidth, vh = window.innerHeight;
          let removed = 0;
          for (const el of Array.from(document.querySelectorAll('body *'))) {
            const cs = getComputedStyle(el);
            if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
            const r = el.getBoundingClientRect();
            if (r.width < 120 || r.height < 40) continue;
            const area = (r.width * r.height) / (vw * vh);
            const text = (el.innerText || '').slice(0, 3000);
            const z = parseInt(cs.zIndex) || 0;
            const overlay = area > 0.85 && z >= 100 && (cs.backgroundColor.includes('rgba') || parseFloat(cs.opacity) < 1);
            if ((rx.test(text) && (area > 0.04 || z >= 1000)) || (overlay && text.length < 3000)) {
              el.remove(); removed++;
            }
          }
          for (const el of [document.documentElement, document.body]) {
            if (!el) continue;
            el.style.setProperty('overflow', 'auto', 'important');
            el.style.setProperty('position', 'static', 'important');
            el.classList.remove('modal-open', 'no-scroll', 'noscroll', 'overflow-hidden', 'didomi-popup-open', 'sp-message-open');
          }
          return removed;
        })()
    """.trimIndent()
}
