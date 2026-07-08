# 01. 인증 — 회원가입 / 로그인 / JWT 검증 흐름

## 1. 개요 (무엇을, 왜)

이 플랫폼의 API(`POST /api/campaigns` 등)는 아무나 호출하면 안 됩니다. 그래서 **JWT(JSON Web Token) 기반 인증**을 붙였습니다.

- **회원가입**: 이메일 + 비밀번호를 받아 비밀번호를 **BCrypt로 해시**해서 저장하고, 곧바로 JWT 한 장을 발급해 돌려줍니다.
- **로그인**: 저장된 해시와 입력한 비밀번호를 비교해서 맞으면 새 JWT를 발급합니다.
- **이후 모든 요청**: 프론트엔드가 `Authorization: Bearer <토큰>` 헤더를 붙여 보내고, 서버는 서명을 검증해서 "누구인지"를 확인합니다. 서버는 세션을 전혀 저장하지 않습니다(**stateless**) — 토큰 자체가 신분증입니다.

핵심 설계는 **헥사고날(포트-어댑터)** 구조입니다. 도메인 로직(`mail-core`)은 "비밀번호를 해시한다", "토큰을 발급한다"라는 **인터페이스(포트)** 만 알고, 실제 BCrypt/JWT 구현은 `infra` 모듈의 어댑터가 담당합니다. 나중에 해시 알고리즘이나 토큰 방식을 바꿔도 도메인 코드는 한 줄도 안 바뀝니다.

## 2. 흐름

```
[회원가입/로그인]
 브라우저                mail-api                  mail-core                infra
    │  POST /api/auth/signup  │                        │                      │
    ├────────────────────────>│ AuthController         │                      │
    │                         ├───────────────────────>│ AuthService.signup() │
    │                         │                        ├─ hasher.hash(pw) ───>│ BCryptPasswordHasher
    │                         │                        ├─ users.save(user)    │
    │                         │                        ├─ tokens.issue(user)─>│ JwtTokenService (서명)
    │  { token, email, ... }  │<───────────────────────┤                      │
    │<────────────────────────┤ 201 Created            │                      │

[이후 보호된 API 호출]
    │  GET /api/campaigns                              │
    │  Authorization: Bearer eyJhbG...                 │
    ├────────────────────────>│ JwtAuthFilter          │
    │                         ├─ tokens.verify(token)─────────────────────── > JwtTokenService (서명 검증)
    │                         ├─ SecurityContext에 email 저장
    │                         └─> SecurityConfig 규칙 통과 → CampaignController 실행
```

## 3. 단계별 실제 코드

### 3-1. 포트 정의 — 도메인은 인터페이스만 안다

`mail-core`는 "해시가 뭘로 구현되는지" 모릅니다. 계약(인터페이스)만 선언합니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/port/PasswordHasher.java`
```java
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
```

`mail-core/src/main/java/io/github/ahrimjang/mail/core/port/TokenService.java`
```java
public interface TokenService {

    /** Issues a signed JWT whose subject is the user's email. */
    String issue(User user);

    /** Verifies a token, returning the email subject if valid, else empty. */
    Optional<String> verify(String token);
}
```

### 3-2. 유스케이스 — AuthService (회원가입/로그인 로직)

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/AuthService.java`
```java
    public AuthResponse signup(SignupRequest r) {
        if (r.email() == null || r.email().isBlank()
                || r.password() == null || r.password().isBlank()) {
            throw new IllegalArgumentException("email and password are required");
        }
        if (users.existsByEmail(r.email())) {
            throw new IllegalStateException("email already registered: " + r.email());
        }

        String passwordHash = hasher.hash(r.password());
        User user = User.register(r.email(), passwordHash, r.displayName());
        User saved = users.save(user);
        String token = tokens.issue(saved);

        return new AuthResponse(token, r.email(), r.displayName());
    }

    public AuthResponse login(LoginRequest r) {
        User user = users.findByEmail(r.email()).orElse(null);
        if (user == null || !hasher.matches(r.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("invalid email or password");
        }
        return new AuthResponse(tokens.issue(user), user.getEmail(), user.getDisplayName());
    }
```

포인트: **원문 비밀번호는 절대 저장하지 않습니다.** `hasher.hash()` 결과(해시)만 DB에 들어갑니다. 로그인 실패 시 "이메일이 틀렸는지, 비밀번호가 틀렸는지" 구분해서 알려주지 않는 것도 의도적입니다(계정 존재 여부 노출 방지).

### 3-3. 어댑터 1 — BCrypt 해시 구현

`infra/src/main/java/io/github/ahrimjang/mail/infra/security/BCryptPasswordHasher.java`
```java
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}
```

BCrypt는 같은 비밀번호라도 매번 다른 해시를 만들지만(내장 salt), `matches()`로 검증이 가능합니다. 그래서 "해시 두 개를 문자열 비교"하는 게 아니라 반드시 `matches()`를 씁니다.

### 3-4. 어댑터 2 — JWT 발급/검증

`infra/src/main/java/io/github/ahrimjang/mail/infra/security/JwtTokenService.java`
```java
    @Override
    public String issue(User user) {
        Date now = Date.from(Instant.now());
        Date expiration = Date.from(Instant.now().plusSeconds(expirationMinutes * 60));
        return Jwts.builder()
                .subject(user.getEmail())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    @Override
    public Optional<String> verify(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Optional.of(subject);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
```

토큰의 `subject`에 **이메일**을 넣습니다. 검증은 "서명이 우리 비밀키로 만들어졌는가 + 아직 만료 전인가"를 확인하는 것이고, 실패하면 예외 대신 `Optional.empty()`를 돌려줘서 호출부(필터)가 그냥 "비인증 상태"로 흘려보내게 합니다. 비밀키/만료시간은 `mail-api/src/main/resources/application.yml`의 `app.jwt.*` 설정에서 옵니다.

### 3-5. 공개 엔드포인트 — AuthController

`mail-api/src/main/java/io/github/ahrimjang/mail/api/auth/AuthController.java`
```java
    /** Register a new user and return a freshly issued token. */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@RequestBody SignupRequest request) {
        AuthResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Authenticate an existing user and return a freshly issued token. */
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        return authService.login(request);
    }
```

컨트롤러는 얇습니다 — HTTP를 도메인 호출로 바꿔주는 역할만 하고, 실제 규칙은 전부 `AuthService`에 있습니다. `@ExceptionHandler`로 `IllegalArgumentException`→400, `IllegalStateException`(이메일 중복)→409로 매핑합니다.

### 3-6. 매 요청 검증 — JwtAuthFilter

`mail-api/src/main/java/io/github/ahrimjang/mail/api/auth/JwtAuthFilter.java`
```java
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            tokens.verify(token).ifPresent(email -> SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(email, null, List.of())));
        }
        chain.doFilter(request, response);
    }
```

모든 요청마다 실행되는 서블릿 필터입니다. 토큰이 유효하면 Spring Security의 `SecurityContext`에 "이 요청의 주인은 이 이메일"이라고 기록합니다. **여기서는 거부하지 않고** 그냥 다음으로 넘깁니다 — 거부 여부의 결정은 아래 `SecurityConfig` 규칙이 합니다.

### 3-7. 접근 규칙 — SecurityConfig

`mail-api/src/main/java/io/github/ahrimjang/mail/api/auth/SecurityConfig.java`
```java
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/health", "/api/unsubscribe/**", "/api/track/**", "/api/webhooks/**").permitAll()
                        // uploaded template images: recipients' mail clients fetch these unauthenticated
                        .requestMatchers("/uploads/**").permitAll()
                        .anyRequest().authenticated())
                // Missing/expired JWT must read as 401 (unauthenticated), not Spring's
                // default 403 — the frontend keys its "force re-login" behavior on 401.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, e) -> response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
```

`permitAll` 목록이 곧 "공개 API 목록"입니다: 인증 자체(`/api/auth/**`), 헬스체크, **수신자가 로그인 없이 눌러야 하는 것들**(수신거부 링크, 오픈/클릭 트래킹, 반송 웹훅), 그리고 업로드된 템플릿 이미지(`/uploads/**` — 수신자의 메일 클라이언트가 토큰 없이 이미지를 가져가야 하므로 공개). 나머지는 전부 JWT 필수.

`authenticationEntryPoint`를 명시한 것도 의도적입니다: Spring Security의 기본값은 미인증 요청에 **403**을 주는데, 프론트엔드(`src/api.ts`)는 **401**을 신호로 토큰을 지우고 재로그인 화면으로 보내기 때문에, 토큰 없음/만료를 401로 내려주도록 바꿨습니다.

## 4. 설계 포인트 (왜 이렇게)

- **포트-어댑터 분리**: `AuthService`는 BCrypt도 jjwt도 import하지 않습니다. 테스트할 때 가짜 `PasswordHasher`를 꽂을 수 있고, 프로덕션에서 Argon2나 OAuth로 바꿔도 core는 그대로입니다.
- **Stateless 세션**: 서버가 세션을 안 들고 있으므로 API 서버를 여러 대로 늘려도 "어느 서버로 가든" 토큰만 있으면 됩니다. CSRF를 끈 것도 세션 쿠키를 안 쓰기 때문입니다.
- **subject = 이메일**: 이 MVP에서는 사용자 식별자로 이메일을 그대로 씁니다. 단순하지만, 이메일 변경 기능이 생기면 user id로 바꿔야 하는 지점입니다.
- **필터는 인증만, 인가는 규칙이**: `JwtAuthFilter`가 401을 직접 던지지 않는 이유 — 공개 경로 요청에 잘못된 토큰이 붙어 있어도 통과시켜야 하기 때문입니다. 거부는 `anyRequest().authenticated()`가 담당합니다.

## 5. 확인 방법

mail-api(:8080)를 띄운 뒤:

```bash
# 1) 회원가입 → 토큰이 담긴 JSON 응답
curl -s -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"me@test.com","password":"secret123","displayName":"Me"}'
# → {"token":"eyJhbGciOi...","email":"me@test.com","displayName":"Me"}

# 2) 로그인 (같은 계정으로)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"me@test.com","password":"secret123"}' | jq -r .token)

# 3) 토큰 없이 보호된 API → 401 (미인증 — 프론트엔드는 이 401을 보고 재로그인 화면으로 보냅니다)
curl -i http://localhost:8080/api/campaigns

# 4) 토큰과 함께 → 200 + 캠페인 목록
curl -s http://localhost:8080/api/campaigns -H "Authorization: Bearer $TOKEN"
```

화면으로는: 프론트엔드(:5173)에서 회원가입/로그인하면 토큰이 `localStorage`에 저장되고, 이후 캠페인 화면의 모든 호출에 자동으로 `Authorization` 헤더가 붙습니다. 브라우저 개발자도구 → Network 탭에서 헤더를 직접 확인할 수 있습니다.
