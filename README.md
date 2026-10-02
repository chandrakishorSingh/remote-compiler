## Version 1 - DONE

- we'll create a very simple and minimal version of this software for the first version and after that we'll incrementally add more features and make it more robust.
- we'll go with the following:
	- Spring Boot project — single endpoint that accepts code + language, runs it, returns output (no auth, no DB, no sandbox — just Runtime.exec() locally)
	- Go CLI — simple HTTP POST with code, prints the response
	- Dockerfile — containerize the Spring Boot app

## Version 2 — roadmap

- [x] use a `status` field in the execution's output to indicate how the execution terminated (successfully completed, timedout etc.)
- [ ] add a database to store user and execution info
- [ ] add authentication: email/password first then oauth2 login for google/github on top
- [ ] clear apis for execution service, for eg.
	- `GET /api/v1/executions` — current user's history, paginated (`page`, `size`, `sort`), never another user's rows
	- `GET /api/v1/executions/{id}` — one execution
	- `GET /api/v1/languages` — supported languages, so the web client's dropdown is not hardcoded
- [ ] add a web client built with react/nextjs
- [ ] sandboxed execution
	- container per execution: `--network=none`, `--memory`, `--cpus`, `--pids-limit`, read-only root + small tmpfs, non-root user, hard kill on timeout
	- can use gVisor/firecracker
- [ ] add more languages
	- c++, java, javascript
