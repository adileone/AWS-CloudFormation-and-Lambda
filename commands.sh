#!/bin/sh
aws cloudformation package --s3-bucket samplewebapptest --output-template-file packaged.yaml --template-file template.yaml
aws cloudformation deploy --template-file packaged.yaml --stack-name samplewebapptest
