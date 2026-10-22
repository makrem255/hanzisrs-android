package com.example.util

import android.content.Intent
import android.net.Uri

/**
 * How the app asks the device to open an email to the people who made it.
 *
 * The address is a constant rather than a string in the layout for two reasons. It is
 * the one piece of contact information the app publishes, so it should be greppable
 * from one place instead of scattered through a composable; and the intent built from
 * it is a piece of behaviour that can be asserted without a device, which is what
 * [ContactEmailTest] does.
 *
 * Nothing here sends anything. The intent is a request for the user's own email app
 * to open a draft; the user sends it, or does not.
 */
object ContactEmail {

    /**
     * The address to write to.
     *
     * Kept in sync with `R.string.contact_email`, which is what the screen shows.
     * The screen reads the resource so the visible text can be localised; this
     * constant is the fallback the intent builder uses when it is given no address,
     * so a caller cannot accidentally build a `mailto:` with no recipient.
     */
    const val ADDRESS: String = "makrembensalem2008@gmail.com"

    /**
     * The `mailto:` URI for [address].
     *
     * The address is trimmed but not escaped. Escaping is the wrong tool here: it
     * turns the `@` into `%40`, which is a legal URI and an address no mail client
     * will show the learner as an address. A `mailto:` with a plain `@` is what
     * every mail app expects.
     */
    fun mailtoFor(address: String = ADDRESS): String =
        "mailto:" + address.trim()

    /**
     * An intent that opens the user's email app with [address] as the recipient.
     *
     * `ACTION_SENDTO` with a `mailto:` URI, which is the documented way to ask for
     * a mail app: it names the app that can handle mail rather than anything that
     * can share text, so a learner who has a mail client but no share sheet still
     * gets one. No subject and no body, so the draft opens empty and the learner
     * writes what they want to say.
     */
    fun intentFor(address: String = ADDRESS): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse(mailtoFor(address)))
}
