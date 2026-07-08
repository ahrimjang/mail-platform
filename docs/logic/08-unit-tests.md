# 08. 단위 테스트 가이드 — 클래스·메소드별 해설

`mail-core/src/test/java/io/github/ahrimjang/mail/core/service/` 의 13개 테스트 클래스, 92개 테스트를 메소드 단위로 설명합니다.

## 공통 스타일

- **순수 단위 테스트**: Spring 컨텍스트 없음. JUnit 5(`@Test`) + Mockito(`@Mock` 포트) + AssertJ(`assertThat`). 전체 스위트가 수 초 안에 끝납니다.
- **포트만 mock**: 저장소·발송기·큐 같은 포트 인터페이스는 mock으로 대체하고, **조립 로직이 중요한 협력자(`TrackingRewriter`, `TemplateRenderer`)는 실제 인스턴스**를 씁니다 — 만들어지는 HTML/문자열이 현실과 같아야 의미 있는 검증이 되기 때문.
- **행위 검증**: 반환값보다 "포트에 무엇이 전달됐는가"를 `verify(...)` + `ArgumentCaptor`로 잡아 확인합니다.
- 실행: `./gradlew :mail-core:test` (리포트: `mail-core/build/reports/tests/test/index.html`)

---

## 1. MailDispatchServiceTest (11개) — 발송 파이프라인의 심장

준비물: mock 포트 5개(messages/campaigns/sender/suppressions/contacts) + **실제** TrackingRewriter·TemplateRenderer. 헬퍼 `queuedMessage(contactId)`(id 42 메시지), `campaign(subject, body)`, `counts(pending, sending)`.

| 메소드 | 검증 내용 |
|---|---|
| `dispatchOne_skipsWhenClaimLost` | `claim()`이 false(경합 패배/이미 처리)면 **즉시 리턴** — `findById`도 `sender.send`도 절대 호출 안 됨. 멱등성의 1차 방어선 |
| `dispatchOne_marksFailedWhenCampaignMissing` | 클레임은 이겼지만 캠페인이 삭제된 경우 → 메시지를 `FAILED`로 저장, 발송 안 함 |
| `dispatchOne_marksSuppressedWithoutSending` | 수신자가 억제 목록에 있으면 → `SUPPRESSED`로 저장, `sender.send` 호출 0회 (보내기 전에 명단 확인) |
| `dispatchOne_sendsAndMarksSentOnHappyPath` | 정상 경로: `sender.send`의 핵심 4개 인자(수신자·제목·본문·messageId)를 캡처해 수신자·제목·본문 포함여부·**messageId가 `String.valueOf(42)`**임을 확인(X-Mail-Message-Id correlation용) → 메시지 `SENT` 저장 |
| `dispatchOne_passesCampaignSenderIdentityToTheMailSender` | 캠페인에 발신자(senderName "Acme 팀"/senderEmail "hello@acme.io")가 지정돼 있으면 → `sender.send`의 5·6번째 인자로 **그대로 전달**되는지 (worker가 캠페인별 From을 씀) |
| `dispatchOne_personalizesFromContactVariables` | contactId 있는 메시지: 연락처(Ahrim Jang)를 로드해 `{{firstName}}` → "Ahrim"으로 **발송 시점 렌더** — 제목 "Hi Ahrim", 본문 "Dear Ahrim" 확인 |
| `dispatchOne_rendersEmailVariableForRawRecipient` | contactId 없는 raw 수신자: `{{email}}`만 수신자 주소로 렌더되고, **contacts 포트는 아예 조회 안 함** |
| `dispatchOne_assemblesTrackedHtmlWithUnsubscribeAndOpenPixel` | HTML 조립 검증: 원본 링크가 사라지고 `/api/track/click/{token}?u=`으로 재작성 + `/api/unsubscribe/{unsubToken}` 푸터 + `/api/track/open/{token}` 픽셀이 전부 최종 본문에 포함 |
| `dispatchOne_marksBouncedAndSuppressesOnSendFailure` | `sender.send`가 예외를 던지면 → 메시지 `BOUNCED`(+에러메시지 보존) 저장 **그리고** 그 주소가 reason="bounce"로 억제 목록에 자동 등록 |
| `dispatchOne_completesCampaignWhenQueueDrained` | 처리 후 카운트가 pending=0, sending=0이면 → 캠페인 `COMPLETED` 전환 |
| `dispatchOne_doesNotCompleteWhileMessagesStillInFlight` | pending=0이어도 **sending=1**(다른 소비자가 처리 중)이면 → `COMPLETED` 호출 안 됨. 조기 완료 버그 방지 |

## 2. BounceServiceTest (5개) — 웹훅 바운스 정책

| 메소드 | 검증 내용 |
|---|---|
| `handle_hardBounceWithMessageId_marksBouncedRecordsEventAndSuppresses` | 하드바운스 + messageId(correlation): ① 해당 메시지 `BOUNCED`(+사유) ② `EmailEvent(BOUNCE)` 기록(messageId/campaignId 정확) ③ reason="hard_bounce" 억제 — **3중 반영** 전부 확인 |
| `handle_isIdempotentWhenMessageAlreadyBounced` | 이미 BOUNCED인 메시지에 같은 통보가 또 오면(웹훅 재시도) → 메시지 재저장·이벤트 중복 기록 없음, **억제는 여전히 수행**(억제 자체도 멱등이라 안전) |
| `handle_softBounce_leavesEverythingUntouched` | 소프트바운스(일시 오류)는 재시도 대상 → 메시지·이벤트·억제 **아무것도 안 건드림** |
| `handle_complaintWithoutMessageId_suppressesOnly` | 스팸신고 + messageId 없음 → reason="complaint"로 억제만 수행, 메시지/이벤트 저장소는 접근조차 안 함 |
| `handle_unknownMessageId_stillSuppressesForHardBounce` | 존재하지 않는 messageId여도 크래시 없이 → correlation만 건너뛰고 이메일 기반 억제는 정상 수행 |

## 3. CampaignServiceTest (15개) — 캠페인 생성 3가지 경로 + 발신자/예약/발송 로그

헬퍼: `stubCampaignSaveAssigningId()`(저장 시 id=42 부여), `stubMessageSaveAllAssigningIds()`(100, 101, ... 부여 — 실제 어댑터처럼), `stubViewCounts()`.

| 메소드 | 검증 내용 |
|---|---|
| `create_direct_savesQueuedCampaignAndOnePendingMessagePerRecipient` | 직접 입력: 캠페인이 `QUEUED`로 저장, 수신자 2명 → `MailMessage` 2건(전부 `PENDING` + unsubToken/trackingToken 발급 + contactId null), 반환 뷰의 지표까지 확인 |
| `create_direct_enqueuesOneJobPerSavedMessageId` | **저장된 메시지 id(100, 101)로** 큐에 정확히 1건씩 발행, 그 외 발행 없음 |
| `create_withTemplateId_snapshotsTemplateSubjectAndBody` | templateId 지정 시 요청의 직접 subject/body는 **무시**되고 템플릿 내용이 캠페인에 스냅샷됨 — `{{firstName}}`이 렌더 안 된 원문 그대로 저장되는 것 확인(렌더는 발송 시점 몫) |
| `create_withUnknownTemplateId_throwsNoSuchElement` | 없는 템플릿 → `NoSuchElementException`, **어떤 저장/발행도 일어나기 전에** 실패 |
| `create_withListId_fansOutOneMessagePerMemberCarryingContactId` | 리스트 fan-out: 멤버 2명 → 메시지 2건이 각 멤버의 email과 **contactId(11, 12)**를 담음 + 각각 enqueue |
| `create_withEmptyList_throwsIllegalArgument` | 빈 리스트 → `IllegalArgumentException`, 메시지 저장·발행 없음 |
| `create_withBlankSubjectOrBodyAndNoTemplate_throwsIllegalArgument` | 공백 제목/누락 본문(템플릿도 없음) → 400 계열 예외, **검증이 모든 영속화보다 먼저** 실행됨 |
| `create_withEmptyRecipientsAndNoListId_throwsIllegalArgument` | 수신자 빈 배열/null(리스트도 없음) → 예외, 저장·발행 없음 |
| `create_withSenderFields_storesThemOnTheCampaignAndView` | 발신자 지정("Acme 팀"/hello@acme.io) → 저장되는 Campaign에 senderName/senderEmail이 담기고 반환 뷰에도 그대로 노출 |
| `create_withFutureScheduledAt_persistsMessagesButDefersEnqueue` | 미래 `scheduledAt`: 메시지는 `PENDING`으로 **저장까지만** 하고 캠페인의 enqueuedAt=null → **큐 발행 0회** (나중에 스케줄러가 풀어줌) |
| `create_withPastScheduledAt_sendsImmediately` | 과거 시각의 `scheduledAt`은 예약이 아님 → enqueuedAt 즉시 기록 + 바로 enqueue |
| `recentMessages_mapsRowsAndCapsLimit` | 발송 로그(수신자별 최근 메시지): 행 매핑(id/recipient/status/updatedAt) 확인 + **limit 5000 요청이 포트에 닿기 전에 200으로 클램프** |
| `recentMessages_unknownCampaign_throwsNoSuchElement` | 없는 캠페인의 로그 요청 → 예외, 메시지 저장소 미접근 |
| `sendLog_mapsBucketsAndClampsParams` | 시간 버킷 집계 로그: `SendLogBucket`(time/status/count/detail — 예: BOUNCED 3건 "mailbox full")이 응답으로 매핑되는지 + bucketSeconds(→3600)·limit(→200) **동시 클램프** |
| `sendLog_unknownCampaign_throwsNoSuchElement` | 없는 캠페인의 집계 요청 → 예외, 메시지 저장소 미접근 |

## 4. CampaignScheduleServiceTest (3개) — 예약 캠페인 릴리스

생성 시점에 이미 캠페인+`PENDING` 메시지는 저장돼 있고 enqueue만 미뤄진 상태(위 `create_withFutureScheduledAt...` 참고). worker의 스케줄러가 주기적으로 `releaseDue()`를 호출합니다.

| 메소드 | 검증 내용 |
|---|---|
| `releaseDue_claimsAndEnqueuesEveryPendingMessageOfDueCampaigns` | 기한 도래 캠페인 1건 + `claimForEnqueue` 승리 → 그 캠페인의 PENDING 메시지 id(100, 101) **전부 enqueue**, 반환값 1 |
| `releaseDue_skipsCampaignsWhoseClaimIsLost` | 스케줄러 2대가 동시에 폴링하는 상황: `claimForEnqueue`가 false(경합 패배)면 → **조용한 no-op** — 메시지 조회도 큐 발행도 0회. dispatchOne의 claim과 같은 원자적-클레임 패턴 |
| `releaseDue_withNothingDue_doesNothing` | 기한 도래 캠페인이 없으면 → 아무 것도 안 함, 반환값 0 |

## 5. TransactionalServiceTest (5개) — 단건 발송

실제 `TemplateRenderer` 사용(요청 변수 치환이 계약의 일부라서).

| 메소드 | 검증 내용 |
|---|---|
| `send_rendersTemplateWithRequestVariablesBeforeSavingCampaign` | 캠페인과 달리 **저장 전에 즉시 렌더**: 저장되는 Campaign의 제목이 이미 "Hi Alice", 본문에 코드 "1234"가 치환돼 있음 + `QUEUED` 상태 + 캠페인 id 반환 |
| `send_savesExactlyOneMessageAndEnqueuesItsId` | 정확히 1건의 `PENDING` 메시지(수신자·campaignId 확인) 저장 + 그 id로 정확히 1회 enqueue |
| `send_withNullVariables_rendersPlaceholdersAsEmpty` | variables가 null이어도 크래시 없이 `{{name}}` → 빈 문자열로 렌더 |
| `send_withUnknownTemplateId_throwsNoSuchElement` | 없는 템플릿 → 예외, 저장소·큐 미접근 |
| `send_withInvalidRecipient_throwsIllegalArgument` | `@` 없는 주소/null → 예외, **템플릿 조회조차 하기 전에** 거부 |

## 6. TemplateRendererTest (8개) — `{{변수}}` 치환 순수 로직 (mock 없음)

| 메소드 | 검증 내용 |
|---|---|
| `render_replacesSingleVariable` | `Hello {{firstName}}!` + {firstName: Ahrim} → `Hello Ahrim!` |
| `render_replacesMultipleOccurrencesOfSameVariable` | 같은 변수가 2번 나와도 모두 치환 |
| `render_replacesMultipleDistinctVariables` | 서로 다른 변수 3개 동시 치환 |
| `render_acceptsWhitespaceInsidePlaceholder` | `{{ firstName }}` (공백 포함) 형태도 치환 |
| `render_unknownVariableBecomesEmptyString` | 정의 안 된 변수는 `{{missing}}` 문자 그대로가 아니라 **빈 문자열** |
| `render_nullTextReturnsEmptyString` | null 입력 → NPE 없이 빈 문자열 |
| `render_textWithoutVariablesIsUnchanged` | 변수 없는 텍스트는 원문 그대로 |
| `render_valueContainingDollarAndBackslashIsInsertedLiterally` | 변수 **값**에 `$`·`\`가 있어도 그대로 삽입 — `Matcher.quoteReplacement`가 정규식 메타문자로 오해되는 것을 막는지 확인(이거 없으면 `$100` 같은 값에서 런타임 예외) |

## 7. TrackingRewriterTest (5개) — 링크 재작성/픽셀 순수 로직 (mock 없음)

| 메소드 | 검증 내용 |
|---|---|
| `rewriteLinks_routesHttpsLinkThroughClickEndpointWithEncodedUrl` | `href="https://...?a=1&b=2"` → `{base}/api/track/click/{token}?u=<URL인코딩된 원본>`으로 정확히 변환(쿼리스트링 인코딩 포함) |
| `rewriteLinks_rewritesEveryHttpLink` | 링크 2개짜리 HTML → 둘 다 재작성(split 카운트로 정확히 2회 검증) |
| `rewriteLinks_leavesNonHttpHrefsUntouched` | `mailto:`, `#anchor`는 **건드리지 않음** (http/https만 재작성) |
| `rewriteLinks_leavesHtmlWithoutLinksUnchanged` | 링크 없는 HTML은 원문 그대로 |
| `openPixel_pointsImgAtOpenTrackingEndpoint` | 픽셀이 `<img ...src="{base}/api/track/open/{token}"...>` 형태인지 |

## 8. ContactServiceTest (7개) — 연락처 CRUD + CSV 임포트

| 메소드 | 검증 내용 |
|---|---|
| `create_savesAndReturnsViewOfValidContact` | 정상 생성: 저장된 Contact의 email 캡처 확인 + 반환 뷰의 email/이름 확인 |
| `create_rejectsEmailWithoutAtSign` | `@` 없는 이메일 → `IllegalArgumentException`, 저장 안 됨 |
| `create_rejectsExistingEmail` | 이미 존재하는 이메일 → `IllegalStateException`(API에선 409), 저장 안 됨 |
| `importCsv_countsNewAsImportedAndDuplicateOrInvalidAsSkipped` | 5줄 CSV(신규 3 + 기존중복 1 + 불량주소 1) → `ImportResult(3, 2)`, save는 정확히 3회 |
| `importCsv_withListId_addsEveryValidLineIncludingDuplicatesToList` | listId 지정 시: 신규뿐 아니라 **기존 연락처도** 리스트 멤버십에 추가(2회), 불량 줄은 제외 |
| `importCsv_withoutListId_neverTouchesListMembership` | listId 없으면 멤버십 저장소 접근 0회 |
| `importCsv_lineWithOnlyEmailStillImports` | `email`만 있는 줄(이름 없음)도 정상 임포트, 이름 필드는 null |

## 9. ContactListServiceTest (10개) — 리스트 수정/삭제 + 연락처의 리스트 라벨

리스트 rename/삭제와 `GET/PUT /api/contacts/{id}/lists`(멤버십 집합 교체)의 서비스 계층 검증입니다(07 문서 3-9·3-10 참고).

| 메소드 | 검증 내용 |
|---|---|
| `update_changesNameAndDescription` | rename: 저장되는 ContactList의 name/description이 새 값으로 바뀌고, 반환 뷰에 memberCount까지 채워짐 |
| `update_unknownListThrowsNotFound` | 없는 리스트 수정 → `NoSuchElementException`(API에선 404), **save 0회** |
| `update_blankNameThrowsBadRequest` | 공백 이름 → `IllegalArgumentException`, save 0회 (검증이 저장보다 먼저) |
| `delete_delegatesToRepository` | 삭제는 저장소 `deleteById`에 위임 (멤버십 동반 삭제는 어댑터 몫) |
| `listsOf_returnsListIdsForContact` | 연락처의 소속 리스트 id 목록 반환 |
| `listsOf_unknownContactThrowsNotFound` | 없는 연락처 → `NoSuchElementException` ("contact not found") |
| `replaceListsOf_replacesMembershipsWithDistinctIdsAndReturnsNewSet` | `[1, 2, 1]`처럼 중복이 와도 **distinct된 `[1, 2]`로** `replaceMembershipsForContact` 호출 + 교체 후 집합을 다시 조회해 반환 |
| `replaceListsOf_unknownListThrowsNotFoundAndReplacesNothing` | 대상 집합에 없는 리스트(99)가 섞여 있으면 → 예외, **교체 자체가 실행 안 됨** (전부 검증 후 실행) |
| `replaceListsOf_emptySetClearsMemberships` | 빈 배열 = "모든 리스트에서 빼기" — 정상 동작, 반환도 빈 목록 |
| `replaceListsOf_nullListIdsThrowsBadRequest` | null listIds → `IllegalArgumentException`, 교체 0회 (빈 배열과 null은 다른 의미) |

## 10. AuthServiceTest (6개) — 회원가입/로그인

| 메소드 | 검증 내용 |
|---|---|
| `signup_hashesPasswordAndSavesUser` | 원문 비밀번호가 `hasher.hash()`를 거쳐 **해시로 저장**되는지(저장된 User의 passwordHash="hashed-pw" 캡처) + 토큰 포함 응답 |
| `signup_rejectsDuplicateEmail` | 중복 이메일 → `IllegalStateException`, 저장 안 됨 |
| `signup_rejectsBlankEmailOrPassword` | 공백 이메일/null 비밀번호 → `IllegalArgumentException` |
| `login_withCorrectPasswordReturnsIssuedToken` | 올바른 비밀번호(해시 매칭 성공) → `TokenService`가 발급한 토큰이 응답에 담김 |
| `login_rejectsWrongPassword` | 틀린 비밀번호 → 예외, **토큰 발급 0회** |
| `login_rejectsUnknownEmail` | 없는 계정 → 예외, 토큰 발급 0회 (틀린 비번과 같은 예외 타입 — 계정 존재 여부 노출 방지) |

## 11. TrackingServiceTest (4개) — 오픈/클릭 이벤트 기록

| 메소드 | 검증 내용 |
|---|---|
| `recordOpen_savesOpenEventForKnownToken` | 유효 토큰 → 메시지 역추적 후 `EmailEvent(OPEN)` 저장(messageId/campaignId 정확, url은 null) |
| `recordClick_savesClickEventWithUrlForKnownToken` | 유효 토큰 + 클릭 URL → `EmailEvent(CLICK)`에 **URL 보존** |
| `recordOpen_unknownTokenSavesNothingAndDoesNotThrow` | 모르는 토큰 → 조용히 무시(저장 0회, 예외 없음) — 공개 엔드포인트라 아무 값이나 들어올 수 있음 |
| `recordClick_unknownTokenSavesNothingAndDoesNotThrow` | 클릭도 동일하게 조용히 무시 |

## 12. SuppressionServiceTest (9개) — 수신거부/억제 + 구독 상태 뷰

수신거부 토큰 처리에 더해, `GET/PUT /api/contacts/{id}/subscription`을 받치는 `subscriptionOf`/`updateSubscription`(억제 테이블에서 파생되는 구독 상태 — 07 문서 3-8)을 검증합니다.

| 메소드 | 검증 내용 |
|---|---|
| `suppressByUnsubToken_knownTokenSuppressesRecipientWithUnsubscribeReason` | 유효 수신거부 토큰 → 그 메시지의 수신자가 reason="unsubscribe"로 억제 등록 |
| `suppressByUnsubToken_unknownTokenSavesNothing` | 모르는 토큰 → 저장 0회 (URL 조작으로 남의 주소를 억제시키는 것 방지) |
| `isSuppressed_delegatesToRepository` | 억제 여부 조회가 저장소 `existsByEmail`에 그대로 위임되는지 (true/false 둘 다) |
| `subscriptionOf_suppressedContactReturnsReasonAndSince` | 억제된 연락처 → `SubscriptionView(suppressed=true)` + reason·since가 억제 행의 값 그대로 |
| `subscriptionOf_unsuppressedContactReturnsFalseWithNullFields` | 억제 안 된 연락처 → `suppressed=false`, reason/since는 null (별도 "구독중" 레코드가 없음을 증명) |
| `subscriptionOf_unknownContactThrowsNotFound` | 없는 연락처 → `NoSuchElementException` |
| `updateSubscription_trueRegistersManualSuppression` | 수동 해지: **reason="manual"**로 억제 행 save + 반환 뷰가 저장 후 재조회 값 |
| `updateSubscription_falseDeletesSuppression` | 재구독: `deleteByEmail` 호출(save는 0회) + `suppressed=false` 반환 |
| `updateSubscription_unknownContactThrowsNotFound` | 없는 연락처 → 예외, **save도 delete도 0회** |

## 13. UploadServiceTest (4개) — 템플릿 이미지 업로드

`FileStorage` 포트만 mock. 타입/크기 검증 후 저장을 위임하고 `app.base-url` 기반의 **절대 URL**을 돌려주는 서비스입니다(메일 클라이언트는 수신자 쪽에서 이미지를 가져오므로 상대 경로는 죽습니다).

| 메소드 | 검증 내용 |
|---|---|
| `storeImage_returnsPublicUrlUnderUploads` | png 저장 → `{base-url}/uploads/{저장된 파일명}` 형태의 공개 URL 반환 |
| `storeImage_mapsJpegToJpgExtension` | `image/jpeg` → 확장자 **jpg**로 매핑되어 저장 |
| `storeImage_rejectsUnsupportedType` | `image/svg+xml`(스크립트 실행 가능)·null 타입 → `IllegalArgumentException`, **스토리지 미접근** |
| `storeImage_rejectsEmptyAndOversizeContent` | 빈 파일("empty")·`MAX_BYTES`(5MB) 초과("too large") → 예외, 스토리지 미접근 |

---

## 자주 쓰인 패턴 요약

| 패턴 | 어디서 | 왜 |
|---|---|---|
| `ArgumentCaptor` | 거의 모든 클래스 | mock에 **전달된 객체의 내부 상태**(상태값·사유·토큰)까지 검증 |
| `thenAnswer`로 id 부여 stub | Campaign/Transactional/Contact | 실제 저장소처럼 "저장하면 id가 생기는" 동작을 흉내 — 이후 enqueue(id) 검증이 가능해짐 |
| `verify(..., never())` / `verifyNoInteractions` | 전 클래스 | "안 일어나야 하는 일"(이중 발송, 검증 전 저장, 불필요한 조회)이 정말 안 일어났는지 |
| 실제 협력자 + mock 포트 혼합 | Dispatch/Transactional | 조립·렌더 결과물은 진짜여야 의미가 있고, I/O 경계만 끊으면 충분 |
| 원자적 claim의 승/패 분기 | Dispatch/CampaignSchedule | `claim`/`claimForEnqueue`를 true/false로 stub — 이긴 쪽만 진행하고 진 쪽은 **조용한 no-op**임을 양쪽 다 검증 (동시 소비자/스케줄러 안전) |
