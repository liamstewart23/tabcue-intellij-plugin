# Releasing to the JetBrains Marketplace

## What actually blocks a first release

Only two things, and neither is in the code any more.

**1. Vendor profile.** Sign in to <https://plugins.jetbrains.com> with the JetBrains Account you
already use for the IDE, then either start an upload at
<https://plugins.jetbrains.com/plugin/add#intellij> (which creates the vendor inline on first use)
or create the vendor up front at <https://plugins.jetbrains.com/organizations/new>. The same form
covers individuals and organisations. During creation you:

- pick a **Vendor ID** — permanent, it cannot be changed afterwards;
- accept the **JetBrains Marketplace Developer Agreement**;
- declare **trader / non-trader status**, mandatory under EU Omnibus Directive 2019/2161 and
  required even for a free plugin.

On the trader question: a natural person publishing a free plugin outside their trade or business
is generally a *non-trader*; a for-profit legal entity, or a person acting for professional
purposes, is a *trader*. It is a legal self-declaration, so it is yours to make — but the
distinction is not cosmetic. Under the Digital Services Act, vendors who declare as traders must
additionally complete identity verification (contact details, ID documents, banking details), and
unverified traders are subject to suspension from Marketplace services.

Public name, email and website can be edited later; the approval guidelines require the email and
website to be valid and reachable.

**2. Public repository.** A licence is mandatory to publish, and *if the licence is open source, a
link to the source is required* — so MIT makes a publicly reachable repo a hard requirement. An
inaccessible private repo is a documented rejection reason. This repo is currently local-only:

```bash
gh repo create tabcue --public --source=. --push
```

Note that the earlier history contains a committed build sandbox (~186MB, since removed). Nothing
has been pushed yet, so this is the moment to squash or re-init if you want that out of the
history rather than in a public clone.

The vendor email in `plugin.xml` is set (`hello+jetbrains@liamstewart.ca`); the domain matches the
vendor URL, which is what approval checks.

## Not blocking — deliberately deferred

**Signing** is recommended, not required. An unsigned plugin installs with a warning dialog, so
1.0.0 can ship unsigned and gain a key later:

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

**A publish token** is only needed for *API* uploads, i.e. `publishPlugin` for later releases. The
first upload goes through the web form and needs none. Generate it when you want CLI releases, at
<https://plugins.jetbrains.com/author/me/tokens> — shown once.

**Screenshots** are strictly required only for themes and colour schemes. For this plugin they are
still the highest-leverage part of the listing — see the media section below.

Put the secrets in `.env.build` (already git-ignored, as are `*.pem` and `*.crt`):

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD='…'
export PUBLISH_TOKEN='…'
```

## First upload — must be done by hand

Gradle publishing only works once the plugin page exists, so the first version is uploaded manually
at <https://plugins.jetbrains.com/plugin/add>. Push the public repo *before* opening this form — it
asks for the source URL at submission time, so blocker 2 has to be cleared first. That form is
where you set, once:

- **License**: MIT
- **Source code URL**: the public repo (mandatory for an open-source license)
- **Tags**: required — "Terminal", "UI" fit
- Pricing: leave as free, so you never touch the banking-details flow

Then:

```bash
source .env.build
./build.sh clean build verifyPlugin signPlugin
# upload build/distributions/tabcue-<version>-signed.zip
```

## The listing itself — description vs. media

These live in two different places, which is the thing that costs a release cycle if you assume
otherwise.

**Description → `plugin.xml`.** The `<description>` CDATA block is the plugin page body *and* the
text shown in the IDE's own plugin manager. It supports simple HTML — this listing uses
`<p> <ul> <li> <b> <code> <h3>`, which is the safe subset — and must be wrapped in CDATA.

The description is *also* editable in the Marketplace admin panel, and that is the trap: once you
edit it there, the Marketplace asks whether the web copy or `plugin.xml` should win on subsequent
uploads. Pick `plugin.xml` and keep editing it here, or the two silently diverge and a release
stops updating the page.

Hard rules worth knowing, from the approval guidelines:

- English first. A localised description is allowed only *after* the English one.
- Spelling and grammar are reviewed, and broken media is a rejection reason.
- The description must not still contain the template phrases `Add change notes here` or
  `most HTML tags may be used`.
- **The plugin name must be 30 characters or fewer.** `TabCue: Visual Terminal Tabs` is 28, so
  there are exactly two characters of headroom — a longer subtitle will be rejected.
- The name must not contain `Plugin`, `IntelliJ`, `JetBrains` or a JetBrains product name.

**Screenshots → the admin panel, not the repo.** There is no descriptor field for them: after the
first upload, open the plugin's page in the admin panel and use the **Media** section.

- **1280 × 800 (16:10)** is the size the approval guidelines ask for; the best-practices page gives
  1200 × 760 as a floor. Shoot 1280 × 800 and both are satisfied.
- `.png`, `.jpg`, or an animated `.gif` to show a flow.
- Keep one aspect ratio across the whole set — a mixed set looks broken in the carousel.
- No device frames, no advertising, no text too small to read.
- Media belongs in the Media section rather than embedded in the description: description images
  cannot be zoomed, and they have to work in both the website and the IDE's plugin manager.

Strictly, screenshots are *required* only for themes and colour schemes. For a plugin whose entire
value is visual they are the single highest-leverage part of the listing, and a vague-looking
listing is the most common soft rejection.

Worth capturing for this plugin specifically:

1. Four or five terminal tabs open, each with a different emoji or colour dot — the wall-of-tabs
   problem being solved, which is the whole pitch.
2. An agent session tab beside ordinary shells, since telling those apart is the stated use case.
3. The right-click *Tab Style* menu open, showing the swatches and the emoji row.
4. A tinted terminal on an `ssh prod` tab — the danger-mode case.
5. The rules table in *Settings ▸ Tools ▸ TabCue*.

Take them on a **dark theme with the New UI**, which is what most reviewers and most users see.

**Logo → already in the repo.** `META-INF/pluginIcon.svg` and `pluginIcon_dark.svg` ship inside the
distribution; there is nothing to upload. Both are 40 × 40.

**Also on the admin panel**, none of which is in `plugin.xml`: tags, licence, source URL,
documentation / bug-tracker / forum links, a "getting started" block (HTML allowed), and the
trader-status declaration.

## Later releases

```bash
source .env.build
./build.sh clean build verifyPlugin publishPlugin
```

`publishPlugin` reads `PUBLISH_TOKEN`. A version containing `-` (e.g. `0.4.0-beta.1`) publishes to
the `eap` channel, which users must opt into, so a pre-release cannot land on everyone by accident.

## Every release must pass

```bash
./build.sh clean build verifyPlugin
```

`verifyPlugin` is configured to fail on the same categories the Marketplace validator rejects —
internal, override-only and non-extendable API usages — which the plugin's own defaults do *not*
include. Experimental API usage is deliberately allowed to pass: it is non-blocking for the
Marketplace, and the two usages here are guarded and intentional.

Every submission, including updates, gets human review; the stated turnaround is two business days.

## Known review risks for this plugin

- **Two experimental API usages.** Non-blocking, but visible in the report.
- **Reflection over the reworked-terminal API.** `TerminalTabFacade` reaches
  `com.intellij.terminal.frontend.toolwindow.*` reflectively. This is *not* to defeat an access
  restriction — every symbol is callable with a normal reference, and the verifier reports no
  internal or non-extendable usage. It is for forward compatibility, and it has already earned its
  keep: 262 removed `findTabByContent` and moved the whole API into a content-module jar, and the
  plugin kept working unchanged. Worth stating plainly in the submission notes so a reviewer does
  not read hidden usage as evasion.
- **No `until-build`.** Endorsed by JetBrains, but it does claim compatibility with unreleased
  builds of a drifting experimental API. The two-branch verification matrix is the mitigation.

## Recommended before submitting

- Screenshots (minimum 1200×760). This is a *visual* plugin, so they carry more weight than usual,
  and a vague listing is the single most common rejection reason.
- Consider publishing the first version to the `eap` channel.
