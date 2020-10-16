#!/bin/bash

export _PARAMS=$(jq -j '.Parameters|keys[] as $k | "\($k)=\(.[$k]) "' ../templates/iac/master-parameters-dev.json)
cd ..
mvn clean install -DskipTests
cd templates/iac
aws cloudformation package --template-file template.yml --s3-bucket alessandrotestbucket --output-template-file outDev.yml
aws cloudformation deploy --template-file outDev.yml --stack-name pct-common-app --parameter-overrides $_PARAMS --capabilities CAPABILITY_NAMED_IAM