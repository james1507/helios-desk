.PHONY: up down api web test verify contract
up:
	docker compose --env-file .env -f infra/compose.yml up --build -d --wait

down:
	docker compose --env-file .env -f infra/compose.yml down

api:
	set -a; . ./.env; set +a; COOKIE_SECURE=false SPRING_PROFILES_ACTIVE=dev ./apps/api/mvnw -f apps/api/pom.xml spring-boot:run

web:
	npm --prefix apps/web run dev

test:
	./apps/api/mvnw -f apps/api/pom.xml verify
	npm --prefix apps/web run typecheck
	npm --prefix apps/web run lint
	npm --prefix apps/web test

verify: test
	npm --prefix apps/web run e2e

contract:
	python3 scripts/api-session.py
	./apps/web/node_modules/.bin/openapi-typescript packages/api-contract/openapi.json --properties-required-by-default -o apps/web/lib/schema.d.ts
