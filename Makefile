.PHONY: test fmt run manifests docker up down smoke synth deploy diagrams repo-metadata

test:            ## Run all Scala tests
	sbt -batch scalafmtCheckAll test

fmt:             ## Format Scala sources
	sbt -batch scalafmtAll

run:             ## Run locally with the in-memory store
	sbt -batch "app/run"

manifests:       ## Export build-time graph manifests to ./manifests
	sbt -batch "app/runMain lattice.app.ExportManifests manifests"

docker:          ## Build the container image
	docker build -t lattice:local .

up:              ## Start engine + dynamodb-local
	docker compose up --build -d

down:
	docker compose down -v

smoke:           ## Hit a running instance end to end
	./scripts/smoke.sh $${BASE:-http://localhost:8080}

synth:           ## Synthesize the CDK tenant stack (needs context, see infra/README)
	cd infra && npm ci && npm run synth

deploy:          ## Deploy the tenant stack
	cd infra && npm ci && npm run deploy

diagrams:        ## Re-render architecture diagrams (needs graphviz + pip install diagrams)
	python3 docs/diagrams/render.py

repo-metadata:   ## Set GitHub description + topics (requires gh auth)
	gh repo edit --description "Lattice — a decision-graph engine on Scala 3 + ZIO: declarative DAGs of fetch/compute nodes, build-time manifests, single-record execution history, orchestrated by AWS Step Functions." \
	  --add-topic scala --add-topic zio --add-topic dag --add-topic decision-engine --add-topic step-functions \
	  --add-topic aws-cdk --add-topic ecs-fargate --add-topic dynamodb --add-topic tapir --add-topic platform-engineering
