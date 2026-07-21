---
type: article
description: Point-in-time code-quality/security audit of the shipped bridge and Android code, plus an independent validation pass that confirmed, refuted, or re-bucketed each finding.
status: canonical
authored: 2026-07-21
author: sodre90
tags:
  - article
  - canonical
  - audit
  - security
  - code-quality
---
## Summary

A point-in-time code-quality/security audit of the shipped `bridge` and `android` code (2026-07-08), followed by an independent validation pass (2026-07-09) that re-checked every finding against the actual code and re-bucketed each as confirmed, refuted, overstated, accurate-but-not-a-bug, or subjective. This article consolidates both: the audit's findings by severity, and the validation's verdicts on each. See [pairing-e2e-encryption](./features/pairing-e2e-encryption.md) for the crypto design these findings partly concern, and [app-polish-and-hardening](./features/app-polish-and-hardening.md) for the SQLite migration proposal that predates and addresses the confirmed e2e-store race.

## Body

### Motivation

After the bridge/relay architecture, pairing/e2e encryption, and connectivity features had shipped, a dedicated audit pass was run against the live codebase (not the design docs) to find concrete, path:line-citable bugs and code-quality issues, bucketed by severity (Critical/High/Medium/Low/Polish) separately for `bridge/` and `android/`, plus cross-cutting themes. Because an audit's own findings can themselves be wrong — overstated, already-fixed, or simply misreading the code — a second, independent validation pass re-examined every finding against the code directly and assigned one of five verdicts: CONFIRMED-bug, REFUTED, OVERSTATED, ACCURATE-BUT-NOT-A-BUG, or SUBJECTIVE, plus a final prioritization.

### Findings and verdicts

**Confirmed real bugs (highest priority):**

- **`internal/e2e/store.go` concurrent validate/commit TOCTOU.** The sliding-window replay-counter design (see [pairing-e2e-encryption](./features/pairing-e2e-encryption.md)) specifies validate (read-only) and commit (mutating) as a strict two-phase sequence, with commit only ever running after AEAD verification succeeds. The shipped implementation splits these into two separate lock acquisitions rather than one atomic critical section, so two concurrent requests can both pass validate before either commits — a genuine race that can let a replayed/duplicate frame through, or corrupt the window state. This is the same underlying persistence-layer weakness the [app-polish-and-hardening](./features/app-polish-and-hardening.md) SQLite-migration proposal was designed to close, and confirms that proposal's motivating security claim was accurate, not overstated.
- Several other Critical/High findings in the bridge and Android code, each with a specific path:line citation in the original audit, were independently re-derived by the validation pass reading the code directly (not just trusting the audit's prose) and confirmed as real, reproducible defects.

**Refuted / overstated findings:** a nontrivial fraction of the original audit's findings did not survive independent re-verification — some described behavior that, on direct inspection, the code does not actually exhibit (REFUTED); others described a real code characteristic but overstated its severity or exploitability relative to the actual blast radius (OVERSTATED).

**Accurate-but-not-a-bug:** some findings correctly described the code's actual behavior but that behavior turned out to be an intentional, defensible design choice rather than a defect — for example, the terminal-view width-1 layout for wide characters (see [android-terminal-foundation](./features/android-terminal-foundation.md)) was flagged as a gap in the original design docs but confirmed here as correct, not a bug, since wide-character content was never actually observed live.

**Subjective:** a remaining set of findings were code-quality/style opinions rather than defects — real but not something the validation pass could categorize as right or wrong on the merits.

### Status

Both documents are dated artifacts of a single audit cycle (audit 2026-07-08, validation 2026-07-09) against the codebase as it stood at that time — they are not a live, continuously-updated bug tracker. The validation pass's own final prioritization section is the authoritative ranked list of what to act on first, weighted toward the confirmed bugs (especially the e2e-store race) over the refuted/overstated/subjective buckets. Findings here should be cross-checked against `bd` (this repo's issue tracker) for current open/closed status, since some may since have been fixed and closed as tracked issues.

## References

- [docs/enhancement-audit.md](../docs/enhancement-audit.md)
- [docs/enhancement-audit-validation.md](../docs/enhancement-audit-validation.md)
