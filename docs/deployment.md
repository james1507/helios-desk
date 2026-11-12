# Local deployment

Docker Engine/Compose required; Node 24 and Java 21 only needed for native development. Full demo:

```sh
cp .env.example .env
docker compose --env-file .env -f infra/compose.yml up --build -d --wait
```

The first storage image build compiles pinned upstream MinIO/mc source and can take several minutes. API uses reviewed Flyway migrations and an explicitly enabled dev seed. Subsequent starts preserve PostgreSQL/object volumes. `docker compose down` stops without deleting data.

Native hot reload: stop Docker `api` and `web` first to free their ports, start `db minio minio-init mailpit` through Compose, then run `make api` and `make web` in separate terminals. The API URL is localhost:8085; Next proxies same-origin browser requests. Full Docker frontend uses its compiled `http://api:8085` rewrite destination.

| URL | Purpose |
| --- | --- |
| http://localhost:3100 | Product UI |
| http://localhost:8085/api/v1 | API, cookie authenticated |
| http://localhost:8025 | Mailpit local inbox |
| http://localhost:9006 | MinIO local console |
| localhost:5455 | PostgreSQL local developer port |

Production reference requirements: disable dev profile, supply secret-managed DB credentials, HTTPS/Secure cookies, trusted reverse proxy, isolated management/storage/SMTP network, least-privilege runtime and migration DB roles, backups and tested restore. Compose is explicitly not a production deployment. No real secrets are included.

Flyway failure blocks startup. Never edit applied migrations; add expand/migrate/contract migrations. Roll back application binaries only when compatible with expanded schema; restore a verified backup for destructive recovery.

## Isolated clean-start check

Build the base images first. With Compose 2.24.4+ the verification override removes dependency/API host ports and exposes only web on 3101. The explicit project name creates separate networks and volumes:

```sh
docker compose --env-file .env -f infra/compose.yml build
docker compose --env-file .env -p helios-clean-check -f infra/compose.yml -f infra/compose.verify.yml up --no-build -d --wait
BASE_URL=http://localhost:3101 npm --prefix apps/web run e2e
```

After checking results, remove only this disposable test environment with the following command. It deletes the verification project's synthetic database/object volumes; it preserves the main `helios-desk` demo:

```sh
docker compose --env-file .env -p helios-clean-check -f infra/compose.yml -f infra/compose.verify.yml down --volumes
```
