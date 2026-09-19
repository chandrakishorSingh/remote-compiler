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

## Version 1

- we'll create a very simple and minimal version of this software for the first version and after that we'll incrementally add more features and make it more robust.
- we'll go with the following:
	- Spring Boot project — single endpoint that accepts code + language, runs it, returns output (no auth, no DB, no sandbox — just Runtime.exec() locally)
	- Go CLI — simple HTTP POST with code, prints the response
	- Dockerfile — containerize the Spring Boot app

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

## Version 2 — design changes

- add a field `status` in the api output of execution result which will indicate about the execution result of the program (for ex. whether it got timeout, exceeded memory/time limit/output buffer etc.)
    - replace the `-1` exit code sentinel with an explicit `status` field
    	- what v1 does
    		* when the program is killed after the timeout, `CodeExecutionService` returns `exitCode = -1`
    		* `-1` works as a sentinel because a real unix exit status is a single unsigned byte (0-255), so no program can produce a negative value
    		* the cli then has to decode that magic number: `if result.ExitCode < 0 -> exit 124`
    	- why that is a poor design
    		* one numeric field means two different things: "the program's exit code" and "the program never got to exit"
    		* the meaning lives in prose, not in the api — every new client has to learn the magic number
    		* the alternatives are worse: the real value after `destroyForcibly()` is `137` (128 + SIGKILL), and `124` is what GNU `timeout` uses, but a program can genuinely `exit 137` or `exit 124`, so both are ambiguous
    	- what v2 will do
    		* add a `status` enum to `ExecutionResponse`: `COMPLETED`, `TIMEOUT`, and later `MEMORY_EXCEEDED` / `OUTPUT_LIMIT_EXCEEDED` when the sandbox lands
    		* `exitCode` becomes null unless the program actually exited, so it only ever holds a real exit code
    		* shape: `{"status":"TIMEOUT","exitCode":null,...}` vs `{"status":"COMPLETED","exitCode":3,...}`
    		* the cli branches on `status` instead of the sign of a number; it still maps a timeout to exit code 124 for shell users, but that mapping becomes a cli display decision rather than an api contract
    	- why it is worth doing
    		* self-describing in openapi (an enum documents itself, `-1` needs a footnote)
    		* easier to assert in tests
    		* this is how judge0/piston-style execution services model it
    		* the sandbox will add more non-exit outcomes, and each one would otherwise need another magic number

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
