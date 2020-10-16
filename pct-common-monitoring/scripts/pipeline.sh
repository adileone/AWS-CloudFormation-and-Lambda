#!/bin/sh
aws cloudformation deploy --template-file ../templates/pipeline/pipeline.yml \
	--stack-name pct-common-pipeline \
	--role-arn arn:aws:iam::734516707349:role/WIPOServiceRoleforcloudformation \
	--capabilities CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
	--parameter-overrides NamePrefix=pct-common BudgetUnitCode=0122-12516 TechnicalOwner=claudio.morgia@wipo.int BusinessImpactLevel=3 DataClassification=highly_confidential BusinessUnitName=pct-information-system-division BusinessOwner=murray.leach@wipo.int
