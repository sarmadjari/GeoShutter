package com.saschl.cameragps.utils

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SentryRedactionTest {

    @Test
    fun macAddressesAreRemovedFromEventMessagesExceptionsAndBreadcrumbs() {
        val event = SentryEvent().apply {
            message = Message().apply {
                formatted = "Connect to AA:BB:CC:DD:EE:FF failed"
                message = "Connect to AA:BB:CC:DD:EE:FF failed"
                params = listOf("aa-bb-cc-dd-ee-ff", "no address")
            }
            exceptions = listOf(SentryException().apply { value = "GATT 133 on 11:22:33:44:55:66" })
            breadcrumbs = listOf(Breadcrumb("Remote shutter request failed for 11:22:33:44:55:66"))
        }

        SentryRedaction.redact(event)

        assertEquals("Connect to XX:XX:XX:XX:XX:XX failed", event.message?.formatted)
        assertEquals("Connect to XX:XX:XX:XX:XX:XX failed", event.message?.message)
        assertEquals(listOf("XX:XX:XX:XX:XX:XX", "no address"), event.message?.params)
        assertEquals("GATT 133 on XX:XX:XX:XX:XX:XX", event.exceptions?.single()?.value)
        assertEquals(
            "Remote shutter request failed for XX:XX:XX:XX:XX:XX",
            event.breadcrumbs?.single()?.message,
        )
    }

    @Test
    fun breadcrumbsAreRedactedBeforeTheyAreStored() {
        val breadcrumb = SentryRedaction.redact(Breadcrumb("Device AA:BB:CC:DD:EE:FF connected"))

        assertEquals("Device XX:XX:XX:XX:XX:XX connected", breadcrumb.message)
    }

    @Test
    fun eventsWithoutTextAreLeftAlone() {
        val event = SentryRedaction.redact(SentryEvent())

        assertEquals(null, event.message)
        assertFalse(event.exceptions?.isNotEmpty() == true)
    }
}
