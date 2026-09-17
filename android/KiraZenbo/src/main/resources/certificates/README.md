# Android 6 Hermes trust anchor

`isrg_root_x1.pem` is the complete, unchanged public ISRG Root X1 certificate
from <https://letsencrypt.org/certs/isrgrootx1.pem>.
Its DER SHA-256 fingerprint is
`96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6`,
verified against [ISRG CP/CPS section 1.1](https://letsencrypt.org/documents/isrg-cp-cps-v6.2/#11-overview).

Android 6 predates [built-in X1 trust](https://letsencrypt.org/docs/certificate-compatibility/).
Native Hermes clients first use platform trust, then perform full certificate
validation against this root. The client stays on its configured HTTPS origin
and retains OkHttp hostname verification. This does not install a system CA or
change the explicit SPKI pin mode. Root trust policy should be reviewed when
updating the app; it is not a short-lived server certificate pin.

ISRG owns this certificate. [CP/CPS section 9.5](https://letsencrypt.org/documents/isrg-cp-cps-v6.2/#95-intellectual-property-rights)
permits its complete reproduction and redistribution on a non-exclusive,
royalty-free basis. The certificate is not covered by the project's Apache license.
