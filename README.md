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
