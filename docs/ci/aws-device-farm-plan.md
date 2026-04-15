# CRA-7 — AWS Device Farm Setup Plan

**Status**: Phase 0 — prérequis
**Ticket**: [CRA-7](https://linear.app/crackn/issue/CRA-7)
**PR**: [#77](https://github.com/cracknco/kompressor/pull/77)
**Région AWS**: `us-west-2` (Device Farm est single-region, pas d'endpoint EU)
**Budget cible**: `MOBILE_CI_BUDGET_MONTH=75` USD

## Légende
- **[TOI]** = action manuelle Rachid
- **[MOI]** = Claude commit / code
- **[TOI+MOI]** = collab guidée

---

## Phase 0 — Prérequis (30 min) — [TOI]
- [ ] Compte AWS actif, accès région `us-west-2`
- [ ] Accès admin IAM (création rôles OIDC + project Device Farm)
- [ ] Apple Developer account avec capacité de signer un `.ipa` de test
- [ ] Accès admin repo GitHub `cracknco/kompressor` (secrets + variables + env)
- [ ] Carte bancaire AWS + limite dépense mensuelle souhaitée ($75 suggéré)

## Phase 1 — AWS Device Farm setup (~20 min)
- [ ] **[TOI]** Créer projet Device Farm via CLI → `projectArn`
- [ ] **[TOI]** Créer Device Pool iPhone A10+ (iPhone 12/13/14, iOS 16+) → `devicePoolArn`
- [ ] **[MOI]** Commit `.aws/devicefarm-pool.json` (définition reproductible)

## Phase 2 — IAM OIDC pour GitHub Actions (~15 min)
- [ ] **[MOI]** CloudFormation `infra/aws-oidc-github.yml`:
  - Identity Provider `token.actions.githubusercontent.com`
  - Role `kompressor-ci-devicefarm` scopé `repo:cracknco/kompressor:*`
  - Policy minimale: `devicefarm:ScheduleRun/GetRun/ListArtifacts/CreateUpload/GetUpload`
- [ ] **[TOI]** `aws cloudformation deploy` → `RoleArn`
- [ ] **[TOI]** GitHub → Variables (pas Secrets — ce sont des ARN):
  - `AWS_ROLE_ARN`
  - `AWS_DEVICEFARM_PROJECT_ARN`
  - `AWS_DEVICEFARM_POOL_ARN`

## Phase 3 — Budget guard (~10 min)
- [ ] **[MOI]** `scripts/ci/check-devicefarm-budget.sh` (lit Cost Explorer, fail si > budget)
- [ ] **[MOI]** Job `budget-gate` qui précède `ios-device-smoke` et skip gracieusement
- [ ] **[TOI]** CloudWatch Billing Alarm → SNS → email `rachid@switchy.be` (seuils 50/80/100%)
- [ ] **[TOI]** GitHub Variable `MOBILE_CI_BUDGET_MONTH=75`

## Phase 4 — Signature iOS device (~30 min)
- [ ] **[TOI]** App ID `co.crackn.kompressor.devicetest` sur developer.apple.com
- [ ] **[TOI]** Provisioning profile Development (wildcard si possible)
- [ ] **[MOI]** Doc `docs/ci/ios-device-signing.md`
- [ ] **[TOI]** GitHub Secrets (base64):
  - `IOS_CERT_P12`
  - `IOS_CERT_PASSWORD`
  - `IOS_PROVISIONING_PROFILE`

## Phase 5 — Workflow `.github/workflows/ios-device-smoke.yml` (~45 min) — [MOI]
- [ ] Job `macos-latest` :
  - Checkout + setup Xcode
  - Gradle Kotlin/Native → `iosArm64` test binary (real device)
  - `xcodebuild build-for-testing` → host `.ipa` + XCTest bundle `.zip`
  - Sign avec certs secrets
  - Device Farm `CreateUpload` × 2 (APP + TEST)
  - `ScheduleRun` avec testSpec YAML filtrant `@Tag("deviceOnly")`
  - Poll `GetRun` jusqu'à `COMPLETED`
  - Download artefacts JUnit → `artifacts/ios-device/junit.xml`
- [ ] Upload artefact GitHub + `dorny/test-reporter`

## Phase 6 — Merge gate (~10 min) — [MOI]
- [ ] `ios-device-smoke` ajouté au merge gate `pr.yml`
- [ ] Gate conditionnel : budget épuisé → reporte sans bloquer merge

## Phase 7 — Code tests (~20 min) — [MOI]
- [ ] Vérifier `Hdr10ExportRoundTripTest` / `Surround51RoundTripTest` / `Surround71RoundTripTest` taggés `@Tag("deviceOnly")`
- [ ] Vérifier skip simulator

## Phase 8 — Doc + Linear (~10 min) — [MOI]
- [ ] `docs/ci/aws-device-farm.md` runbook
- [ ] Comment PR #77 avec lien runbook
- [ ] Update CRA-7 DoD items

## Phase 9 — Validation (~1 semaine) — [TOI+MOI]
- [ ] Observer 5-10 PRs, mesurer coût réel
- [ ] Ajuster budget si nécessaire
- [ ] Memory save du coût observé

---

## Artefacts de config à remplir au fur et à mesure

```
AWS_ACCOUNT_ID            = 437655433978
AWS_REGION                = us-west-2
AWS_IAM_USER              = crackrachid
DEVICEFARM_PROJECT_ARN    = arn:aws:devicefarm:us-west-2:437655433978:project:e5295e19-f8c6-40a7-b108-025f108e19fb
DEVICEFARM_POOL_ARN       = arn:aws:devicefarm:us-west-2:437655433978:devicepool:e5295e19-f8c6-40a7-b108-025f108e19fb/d57a8b94-cb5a-4e3d-b1a1-fe03027f7562
GITHUB_OIDC_ROLE_ARN      = arn:aws:iam::437655433978:role/kompressor-ci-devicefarm
GITHUB_OIDC_ROLE_ARN      = <à remplir Phase 2>
APPLE_TEAM_ID             = <à remplir Phase 4>
APPLE_BUNDLE_ID           = co.crackn.kompressor.devicetest
```

## Notes
- Device Farm region-locked us-west-2 (pas d'endpoint EU). RGPD OK car pas de data perso.
- Resigning auto dispo côté Device Farm si cert+profile fournis (plus simple que signer nous-mêmes).
- Coût estimé : ~$0.17/device-min × ~5 min × ~30 PRs/mo = **$25-50/mo** attendu.
