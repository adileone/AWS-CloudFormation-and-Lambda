#!/bin/bash

aws cloudformation package --template-file template.yml --s3-bucket octrspinfracode --output-template-file out.yml
aws cloudformation deploy --template-file out.yml --stack-name pct-app-alarms --capabilities CAPABILITY_NAMED_IAM