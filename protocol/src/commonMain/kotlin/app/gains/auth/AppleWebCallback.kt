package app.gains.auth

/**
 * The callbacks of Sign in with Apple's web flow (`AppleWebFlow`) that the app and the server
 * must agree on exactly. The desktop's loopback callback isn't here: the server accepts any port
 * on `127.0.0.1`, so there is nothing to agree on.
 */
object AppleWebCallback {
    /**
     * Where the browser is sent back to on Android: an App Link, so that the browser hands the
     * callback to the app signed with our key and to nothing else, unlike a custom scheme any app
     * could claim. The server accepts exactly this URL (`AppleWebSignIn.APP_CALLBACKS`), the
     * manifest's `SignInCallbackActivity` claims it, and the page at this address on the site
     * catches the browser when the link is not verified (a debug build) and offers the app the
     * same callback as an `intent:` link addressed to our package.
     */
    const val ANDROID = "https://gains.gerra.sh/auth/done"
}
