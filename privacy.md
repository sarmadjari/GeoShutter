**Privacy Policy**

This privacy policy applies to the GeoShutter app ("Application") for Android and iOS devices,
created by Sarmad Jari ("Service Provider") as an open‑source service. GeoShutter is based on the
open-source Alpha GPS app by Saschl. This service is provided "AS IS".

**What Data Is Collected and Why**

The Application is built with privacy by design. It **does not collect or sell any data for
advertising, tracking, or other commercial purposes**, and it does not require an account.

The Application only processes data that is strictly necessary for:

* Providing geotagging functionality for your Sony or Fujifilm camera
* Diagnosing crashes and technical problems, **only if you enable error reporting**

**Location Data**

The Application uses your device's location only to provide geotagging functionality:

* Location is used to transfer GPS coordinates to your connected camera over Bluetooth.
* Location is only accessed while at least one connected camera takes it: not while a connected
  Fujifilm camera has its location sync turned off, or while a connected Sony camera is switched
  off. Location collection stops as soon as no connected camera needs it anymore, also when the
  camera is kept in "Always On" mode on Android. A Fujifilm camera that stays connected in standby
  (switched off or asleep, with its CONNECT WHILE POWER OFF setting on) keeps receiving your
  location, by default once a minute, so that its next photo is tagged right away.
* When a camera connects, the Application also sets its date, time and time zone from your phone.
* The Application does **not** store location data permanently, neither on your device nor with
  the Service Provider, and it does not write location data to its logs.
* Location data is **not** sent to the Service Provider or to any third party by the
  Application, and it is not included in error reports.

**Data Stored on Your Device**

To reconnect to your cameras, the Application stores on your device the list of cameras you
added (their Bluetooth address or iOS identifier, name, and per-camera settings) and its app
settings. The Application also keeps a technical log on your device, which you can view and
delete in the app; older entries are removed automatically. The log contains no location data.
This data is not sent anywhere; the only exception is the error reporting described below, if you
enable it.

**Crash and Problem Analysis (Sentry)**

Error reporting is **off unless you allow it**. On first start, the Application asks whether you
want to send anonymous error reports. You can change your choice at any time in the Application's
settings ("Enable Error Reporting"); turning it off takes full effect after the Application is
restarted. The FOSS build of the Android app (the version intended for F-Droid) does not contain
Sentry at all and never sends error reports.

If you enable it, diagnostic data is sent to the Sentry error reporting service, hosted in EU
data centers:

* Crash reports, error messages and stack traces
* Technical log messages and events leading up to a problem
* Basic technical information about your device and app, such as the operating system version,
  app version, device model and app sessions (for example, time spent in the Application)

This data is used **only** to analyze crashes and technical problems:

* No personal data such as precise location history, device MAC addresses, or IP addresses is
  collected or stored for profiling.
* Data is **never** used for advertising, tracking, or sold to third parties.

For more details about Sentry’s handling of data, please see:

* [Sentry Privacy Policy](https://sentry.io/privacy/)

**Other Services**

Depending on your device and the version of the Application, it may also use:

* [Google Play Services](https://www.google.com/policies/privacy/) (Google Play version of the
  Android app only): location through the Fused Location Provider (you can switch to the Android
  platform location provider in the settings) and the in-app review dialog.
* Apple services (iOS only): Core Location for location and the App Store review prompt. See
  [Apple's Privacy Policy](https://www.apple.com/legal/privacy/).

Links in the Application open external websites in your browser (GitHub for documentation and
problem reports, and Buy Me a Coffee for donations to Saschl, the author of Alpha GPS), where
those websites' privacy policies apply.

Please refer to these providers' privacy policies for details about their processing.

**Opt-Out Rights**

You can stop all collection and processing of information by the Application at any time by:

* Disabling error reporting in the settings, and/or
* Disabling the Application ("Enable App") or revoking its location permission, and/or
* Uninstalling the Application using the standard process on your device or app store.

You can delete the Application's on-device log at any time in the log viewer.

**Children**

The Application is not directed to children under the age of 13, and the Service Provider does not
knowingly collect personally identifiable information from children under 13 years of age. If you
believe that a child under 13 has provided personal information, please contact the Service Provider
(see below) so that it can be removed.

**Security**

The Service Provider takes the protection of your data seriously and implements reasonable technical
and organizational measures to safeguard the diagnostic information that is processed and
maintained.

**Changes**

This Privacy Policy may be updated from time to time. Any changes will be published on this page.
Continued use of the Application after changes are posted will be treated as acceptance of the
updated Privacy Policy.

This privacy policy is effective as of 2026-09-29.

**Your Consent**

By using the Application, you consent to the processing of your information as described in this
Privacy Policy, now and as amended from time to time.

**Contact Us**

If you have any questions about this Privacy Policy or privacy while using the Application, please
contact the Service Provider by opening an issue at
**[github.com/sarmadjari/GeoShutter/issues](https://github.com/sarmadjari/GeoShutter/issues)**.
Issues are public: please don't include personal information in them.

---

This privacy policy page is based on the Alpha GPS privacy policy, which was originally generated
by [App Privacy Policy Generator](https://app-privacy-policy-generator.nisrulz.com/), and has been
adapted to reflect the Application's actual data usage and strong privacy focus.
