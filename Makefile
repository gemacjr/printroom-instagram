# Local: LocalStack + Redis + API + worker + web.   Override ports, e.g. `make up LOCALSTACK_PORT=4567`.
LOCALSTACK_PORT ?= 4566
export LOCALSTACK_PORT

.PHONY: up down logs seed test test-backend test-frontend postman tf-validate deploy

up:            ## Build and start everything (http://localhost:3000)
	docker compose up -d --build

down:          ## Stop and remove containers (LocalStack state is ephemeral)
	docker compose down

logs:
	docker compose logs -f api worker

seed:          ## Demo users (log in as demo / password123)
	./scripts/seed.sh

test: test-backend test-frontend

test-backend:
	cd backend && mvn -q test

test-frontend:
	cd frontend && npm test && npm run typecheck && npm run lint

postman:       ## Run the Postman collection against the local stack
	npx --yes newman run postman/Printroom.postman_collection.json \
	  -e postman/Local.postman_environment.json --working-dir postman

tf-validate:
	cd infra/terraform && terraform init -backend=false -input=false >/dev/null && terraform validate

deploy:        ## Build, push and deploy to AWS (billable!)
	./scripts/deploy-aws.sh
