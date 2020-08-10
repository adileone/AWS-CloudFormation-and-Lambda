#!/bin/sh
aws cloudformation deploy --template-file ../templates/pipeline/pipeline.yml \
	--stack-name MonitoringPipeline \
	--capabilities CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
	--parameter-overrides NamePrefix=pct-common BudgetUnitCode=0122-12516 TechnicalOwner=claudio.morgia@wipo.int BusinessImpactLevel=3 DataClassification=highly_confidential ProductionAccount=770725503646 BusinessUnitName=pct-information-system-division BusinessOwner=murray.leach@wipo.int
