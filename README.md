## Tech Stack

- Web service
	+ Spring boot
		* to manage all the logic related to handling the input code, validating it
		* selecting the appropriate compiler/interpreter for executing the code
		* allocating appropriate memory, time for the program to run
		* sending back the output to client
		* storing the request info like user info, code, output, execution stats like memory used and execution time in database
		* authentication of user
		* rate limiting the user
		* authenticating the user(signup/login) using email/password and third party providers like google, github, microsoft etc.
	+ database
		* postgresql
			* to store user info
			* to store user request information like code, programming language
			* to store program execution information like memory used and execution time
	- containerization
		+ docker
	- message queue
		- rabbitmq
			- it decouples api from execution. queue requests, handle backpressure, enable rate limiting
	- redis
		- session cache, rate-limit counters, fast pub/sub for execution status
	- sandbox runtime
		- gvisor/firecracker
			- these are sandbox runtime that replace docker's default container runtime(runc).
			- we'll use docker like normal but will swap the runtime(docker run --runtime=runs ...) so that it will spins up a gvisor sandbox instead of a regular container.
			- they provide a stronger security boundary(a lightweight VM or kernel level isolation) than standard docker containers.
		- some points
    		- need to allocate some fixed amount of disk space per execution because we don't want the executing process to write a lot of output
	- websocket/sse
		- stream execution output back in real-time instead of polling
	- database migration tool
		- flyway/liquibase
		- version controlled schema changes for postgresql
	- monitoring
		- prometheus + grafana
		- track execution time, failure rates, resource usage
	- reverse proxy
		- nginx/traefik
		- tls termination, rate limiting, request routing
		- it can do following things:
			- tls termination: handles certificate management, http/2, ocsp stapling
			- rate limiting at edge: blocks abusive traffic before it reaches the app(saves cpu cycles)
			- path/header-based routing: route /api/* to spring boot, /metrics to prometheus and /static/* to a cdn
			- load balancing: distribute traffic across multiple spring boot instances
			- websocket upgrades, gzip, caching - done more efficiently in nginx/traefik than in the JVM
	- api docs
		- openapi/swagger
		- auto generate client sdks, document endpoints

- cli client
	+ Go lang
		* it will be used to authenticate user(signup/login)
		* users can send request containing the code to execute to the server and will receive the code output along with execution information like memory/cpu time which they can display on the terminal.
		* it can request to server to show stats about its past sessions(one session info will have the time of req, input code, output, memory used, time taken for execution etc.)

## Testing

- principles
	- test observable behaviour through the public api, not private methods
	- pyramid: many fast tests, few slow ones
	- one behaviour per test, named `method_condition_expectedResult`, written as arrange/act/assert
	- don't test the framework itself (jackson mapping, spring routing) or plain accessors

- layers
	+ unit tests — no spring context, runs in milliseconds, named `*Test`
		* tools: junit 5 + assertj (both come from `spring-boot-starter-webmvc-test`)
		* cases: `Language.fromId` matching case-insensitively and throwing for unknown ids; `readCapped` at the 64k boundary (65536 chars -> `truncated=false`, one more char -> `true`); invalid utf-8 bytes replaced with the replacement character
	+ web slice tests — spring mvc only, service mocked, named `*Test`
		* tools: `@WebMvcTest(ExecutionController.class)` + `MockMvcTester` + `@MockitoBean CodeExecutionService`
		* cases: blank code -> 400 with `errors.code`; missing language -> 400; unknown language -> 400 problem detail; GET -> 405; missing content-type -> 415; malformed json -> 400; happy path json field names
	+ integration tests — whole app on a random port, real `python3` process, named `*IT`
		* tools: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `RestTestClient`
		* cases: `print(2+2)` -> stdout `4\n` and exit 0; `sys.exit(3)` -> exit 3 with stderr captured; infinite loop -> exit -1 near the configured timeout; large output -> `truncated=true`; no leftover `exec-*` directory in /tmp afterwards
	+ cli tests (go, once the cli exists) — named `_test.go`
		* tools: `go test ./...`, table-driven tests, `httptest.NewServer` as a fake api, golden files for terminal output
		* cases: flags -> request body; server 400 problem detail -> readable error message; non-zero program exit -> cli exit code
	+ end-to-end (later)
		* packaged jar + real cli binary in one smoke run, driven by a shell script or docker compose

- running them
	- `./mvnw test` -> surefire runs `*Test` only (fast, spawns no processes)
	- `./mvnw verify` -> surefire, then failsafe runs `*IT` too (needs `python3` on PATH)
	- `./mvnw test -Dtest=LanguageTest` -> a single class
	- `go test ./...` inside `cli/` -> cli tests
	- failsafe still has to be added to `server/pom.xml`; the spring boot parent manages its version, so only the plugin entry with the `integration-test` and `verify` goals is needed

- manual smoke test (cli against a running server)
	- setup
		* start the server: `./mvnw spring-boot:run` in `server/`
		* build the cli: `go build -o rcc .` in `cli/`
		* sample programs (create once):
			- `printf 'print(2+2)\n' > /tmp/hello.py`
			- `printf 'import sys\nsys.stderr.write("boom\n")\nsys.exit(3)\n' > /tmp/boom.py`
			- `printf 'while True: pass\n' > /tmp/loop.py`
			- `printf 'for i in range(200000): print(i)\n' > /tmp/big.py`
			- `: > /tmp/empty.py`
	- checks — run `echo $?` after each one to see the exit code
		* `./rcc /tmp/hello.py` -> `4` on stdout, exit 0
		* `./rcc /tmp/boom.py` -> `boom` on stderr, exit 3 (the program's own exit code)
		* `./rcc /tmp/loop.py` -> `execution timed out after 5s` on stderr after ~5s, exit 124
		* `./rcc /tmp/big.py | wc -c` -> 65536, plus `rcc: output truncated at 64KB` on stderr
		* `./rcc -lang ruby /tmp/hello.py` -> `rcc: unsupported language: ruby`, exit 1
		* `./rcc /tmp/empty.py` -> `rcc: request validation failed` and `code: code is required`, exit 1
		* `./rcc -server http://localhost:9999 /tmp/hello.py` -> `connection refused`, exit 1 (no panic)
		* `./rcc /tmp/hello.py > /tmp/out.txt` -> `/tmp/out.txt` holds exactly `4\n` and nothing else
		* `./rcc /tmp/hello.py -lang python` -> usage text, exit 2 (go's flag package stops parsing at the first non-flag arg, so flags must come before the file)
	- raw http checks the cli cannot produce (it always sends a well-formed request)
		* `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/executions` -> 405 (GET is not mapped)
		* `curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/v1/executions -d '{"language":"python","code":"print(1)"}'` -> 415 (no content-type header)
		* `curl -s -X POST http://localhost:8080/api/v1/executions -H 'Content-Type: application/json' -d '{"language":"python","code":"print(2+2)"}'` -> 200 with `"stdout":"4\n"`, `"exitCode":0`, `"truncated":false`
	- after the run: `ls -d /tmp/exec-*` should list nothing — every execution directory is deleted, including after a timeout

- testability change to make first
	- `CodeExecutionService` hardcodes `TIMEOUT_SECONDS = 5` and `MAX_OUTPUT_CHARS = 64 * 1024`, which would make every timeout test take 5 seconds and every truncation test generate 64kb
	- move both into `application.properties` as `execution.timeout` and `execution.max-output-chars`, bound with `@ConfigurationProperties`, and override them in `src/test/resources/application.properties` (e.g. 1s and 200 chars)

- environment requirements
	- `python3` must be installed for the `*IT` tests
	- later layers need docker (testcontainers for postgres/rabbitmq) and the sandbox runtime

- what to test as the stack grows
	- postgres/rabbitmq/redis through testcontainers instead of mocks
	- sandbox: assert that submitted code *cannot* read the host filesystem or open a network connection (both are possible today, so they become regression tests once the sandbox lands)
	- backpressure and rate limits: a load test (k6 or similar) once the queue exists
	- api contract: keep the cli honest against the documented openapi shape
	- ci on github actions: `./mvnw verify` + `go test ./...` on push, with jdk 25 and python3 installed on the runner

- spring boot 4 naming note (older tutorials get these wrong)
	- `@WebMvcTest` lives in `org.springframework.boot.webmvc.test.autoconfigure`
	- `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) replaces the removed `@MockBean`
	- `TestRestTemplate` moved to `org.springframework.boot.resttestclient`; `RestTestClient` is in `org.springframework.test.web.servlet.client`
	- versions currently pulled in: junit jupiter 6.0.3, assertj 3.27.7, mockito 5.23.0

## CI/CD (github actions) — planned

- what it is
	- yaml workflow files in `.github/workflows/`, run by github on events (push, pull request, tag, schedule) on a fresh vm it provides
	- it is a script runner with triggers, not a separate technology to learn deeply

- scope for now — one workflow, triggered on pushes to `main` and on pull requests
	- server: `./mvnw verify` on jdk 25 (ubuntu runners already ship `python3`, which the `*IT` tests need)
	- cli: `go build ./...`, `go vet ./...`, `gofmt -l .` (fail the build if gofmt prints anything)
	- image: `docker build server/` to prove the dockerfile still builds when the pom or source changes
	- value: catches "works on my machine" problems — e.g. the headless-jdk issue that broke the first maven build would have shown up immediately

- later
	- publish the image to `ghcr.io` when a version tag is pushed (this is where git tags and actions meet)
	- release `rcc` binaries for linux/mac/windows from a build matrix, attached to the github release
	- cache `~/.m2` and the go build cache so runs stay short
	- service containers (postgres, rabbitmq) for the v2 integration tests
	- static analysis: golangci-lint (go), spotbugs (java), codeql (security)
	- dependabot for dependency and security updates

- out of scope
	- deployment — there is nowhere to deploy yet
	- sandbox tests — gvisor/firecracker need kernel features hosted runners don't reliably provide, so those stay local
	- scheduled runs — nothing in this project changes on its own

- cost note
	- this repo is private, so actions minutes come from the monthly free allowance (2000 minutes on the free plan; the billing page has the exact figure)
	- a maven build burns ~2-4 minutes per push, a docker build more
	- keep it cheap: trigger on `main` + prs only, cache maven/go, and run the docker job only when `server/**` changes
	- public repos get unlimited minutes, so making this repo public would remove the limit entirely

## Version 1 - DONE

- we'll create a very simple and minimal version of this software for the first version and after that we'll incrementally add more features and make it more robust.
- we'll go with the following:
	- Spring Boot project — single endpoint that accepts code + language, runs it, returns output (no auth, no DB, no sandbox — just Runtime.exec() locally)
	- Go CLI — simple HTTP POST with code, prints the response
	- Dockerfile — containerize the Spring Boot app

## Version 2 — roadmap

- goal: be "correct" first and "efficient" later (v3+). v2 should be close to the final version in functionality.

- decisions taken before starting
	- database comes before auth — auth needs a real `users` table, otherwise it gets written twice
	- rest api design is not a phase — it is an openapi contract written early and implemented continuously
	- spring owns authentication — session cookie for the web client, long-lived api token for the cli
	- sandbox before languages — execution moves into a container per run first, then a language is just config
	- v2 ships 3-4 representative languages, not all 19

- versions available through the spring boot 4.1.1 parent (no version numbers needed in the pom)
	- spring security 7.1.1, spring session 4.1.1, flyway 12.4.0, postgresql driver 42.7.13, testcontainers 2.0.5
	- frontend: next.js 16.3.5, react 19.3.0

- step 0 — add the `status` field (small, do it first)
	- why first: it is a breaking api change and the cli is currently the only client; every later client would otherwise have to learn the `-1` magic number
	- what v1 does
		* when the program is killed after the timeout, `CodeExecutionService` returns `exitCode = -1`
		* `-1` works as a sentinel because a real unix exit status is a single unsigned byte (0-255), so no program can produce a negative value
		* the cli then has to decode that magic number: `if result.ExitCode < 0 -> exit 124`
	- why that is a poor design
		* one numeric field means two different things: "the program's exit code" and "the program never got to exit"
		* the meaning lives in prose, not in the api
		* the alternatives are worse: the real value after `destroyForcibly()` is `137` (128 + SIGKILL), and `124` is what GNU `timeout` uses, but a program can genuinely `exit 137` or `exit 124`
	- what to do
		* `status` enum on `ExecutionResponse`: `COMPLETED`, `TIMEOUT` (later `MEMORY_EXCEEDED`, `OUTPUT_LIMIT_EXCEEDED`, `COMPILE_ERROR`)
		* `exitCode` becomes nullable (`Integer`) — it only ever holds a real exit code
		* the cli branches on `status`; mapping a timeout to exit 124 becomes a cli display decision, not an api contract
	- done when: the smoke checks still pass and no client reads a negative exit code

- step 1 — persistence: postgres + flyway, still no auth
	- why here: it is the foundation auth sits on, and it delivers value on its own (history exists before users do)
	- `spring-boot-starter-data-jpa`, `postgresql`, `flyway-core`; postgres for local dev via `compose.yaml`
	- migrations in `server/src/main/resources/db/migration/` — write the sql by hand, that is the point of learning a migration tool
		* `V1__create_users.sql`: `users(id, email, password_hash, provider, provider_id, created_at)`
		* `V2__create_executions.sql`: `executions(id, user_id nullable, language, code, stdout, stderr, exit_code, status, duration_ms, truncated, created_at)`
	- new `.../persistence` package (entity + repository); the service saves a row after each run; `user_id` stays null until step 2
	- tests: testcontainers starts a real postgres for the `*IT` tests
	- done when: a POST leaves a row in the db and migrations run on a clean database

- step 2 — authentication (spring security 7)
	- why here: the users table now exists, so nothing is throwaway
	- email/password first (filter chain, `PasswordEncoder`, `UserDetailsService`), then oauth2 login for google/github on top
	- web: session cookie backed by spring session, csrf enabled for cookie-authenticated routes
	- cli: api tokens — `V3__create_api_tokens.sql` (hashed token, label, last_used_at), `rcc login` stores one in `~/.config/rcc/config.json`, sent as `Authorization: Bearer`
	- executions start recording `user_id`; decide then whether anonymous execution stays (and is rate-limited by ip) or stops
	- done when: the same endpoint authenticates a browser session and a cli token, and rows carry the right user

- step 3 — api surface + openapi contract
	- why here: the web client needs a contract to build against, and history endpoints need `user_id` to exist
	- `GET /api/v1/executions` — current user's history, paginated (`page`, `size`, `sort`), never another user's rows
	- `GET /api/v1/executions/{id}` — one execution
	- `GET /api/v1/languages` — supported languages, so the web client's dropdown is not hardcoded
	- `springdoc-openapi` for generated docs; cors configured for the next.js dev origin; keep using `ProblemDetail` for errors
	- done when: `rcc history` works against these endpoints and the openapi page documents them

- step 4 — web client (next.js 16 / react 19)
	- why here: it consumes steps 1-3, and it is the biggest new-technology jump, so it should land on a stable api
	- new top-level `web/` directory, app router
	- login/signup pages driven by spring's endpoints; editor page (codemirror or monaco), run button, stdout/stderr panes, history list
	- the session cookie flows through on its own — nothing custom needed
	- done when: a user can sign up, run code and see history in the browser, with the cli still working unchanged

- step 5 — sandboxed execution (the architectural change)
	- why here: it rewrites *how* code runs, so it must come before the language work or the plumbing gets built twice
	- note: this holds only while the app runs on localhost — if it is ever exposed publicly, this step moves to the front
	- container per execution: `--network=none`, `--memory`, `--cpus`, `--pids-limit`, read-only root + small tmpfs, non-root user, hard kill on timeout
	- introduce an `ExecutionRunner` interface with a host implementation (today's code) and a container implementation, chosen by config — keeps tests fast and the migration reversible
	- `status` gains `MEMORY_EXCEEDED` / `OUTPUT_LIMIT_EXCEEDED` — the enum from step 0 pays off here
	- regression tests: submitted code must not read the host filesystem and must not open a network connection (both succeed today, so they prove this step worked)
	- gvisor (`runsc`) is the stronger follow-up once the container path works; it needs no kvm, unlike firecracker
	- done when: both escape attempts fail from inside the sandbox and normal programs still run

- step 6 — more languages (3-4 in v2)
	- why here: with per-execution containers a language is an image plus a config entry
	- `Language` moves from a hardcoded enum to configuration: id, image, source filename, optional compile command, run command
	- add c++ (compile step), java (compile + classpath), javascript — these cover every mechanism; the rest is repetition
	- compile errors are a distinct failure mode from runtime errors: `status` should say `COMPILE_ERROR`
	- done when: `GET /api/v1/languages` lists them and each runs end-to-end from both clients

- deferred to v3 (so v2 does not sprawl) — these are efficiency and scale concerns, not correctness
	- rabbitmq + async execution
	- redis caching and rate-limit counters
	- websocket/sse output streaming
	- prometheus + grafana
	- nginx/traefik
	- horizontal scaling

- languages to add eventually (after the 3-4 in v2, each is mostly a config entry)
	- c, ruby, scala, kotlin, r, c#, rust, go, php, assembly, swift, dart, elixir, erlang, racket, haskell, typescript

## To Learn

- Docker
- Testing framework for spring boot
- GitHub actions

## NOTE

- useful spring boot commands:
    - `./mvnw spring-boot:run`: to run the dev server
    - `./mvnw -v`: gives info. of many of the dependencies like maven, java, OS info this app is running on
    - `./mvnw dependency:tree`: to list the app's dependencies
- spring boot concepts
    - validating req. body
        - @NotBlank means non-null and not only whitespace (@NotNull allows "", @NotEmpty allows " ").
        - The checks run after Jackson builds the record and before your method is called; on failure Spring throws MethodArgumentNotValidException.
        - comes from spring-boot-starter-validation dependency
- Go concepts
    - useful commands
        - a file with go code is called go module
- Git concepts
    - Tags
        - Lightweight — just a name pointing at a commit, nothing else:
        git tag v1.0.0
        
        Annotated — a real object in the database with the tagger's name, a date, a message, and optionally a GPG signature:
        git tag -a v1.0.0 -m "Version 1: server, go cli, dockerfile"
        
        Use annotated tags for releases. The extra metadata is the point: six months from now git show v1.0.0 tells you who marked it and why. Lightweight tags are for private, throwaway bookmarks.
        
        The commands you'd use
        
        git tag                        # list tags
        git tag -n                     # list with their messages
        git show v1.0.0                # tagger, date, message, then the commit
        git log --oneline --decorate   # see tags next to commits in the log
        
        git push origin v1.0.0         # push one tag
        git push --tags                # push all tags
        
        Tags are not pushed by a normal git push — that trips up everyone at least once. Your tag lives only on your machine until you push it explicitly.
        
        To remove one:
        git tag -d v1.0.0                    # local
        git push origin --delete v1.0.0      # remote
        
        Treat tags as immutable once pushed. Moving a published tag means everyone who fetched it has a different idea of what v1.0.0 is.
