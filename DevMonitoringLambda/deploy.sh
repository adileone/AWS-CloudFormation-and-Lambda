mvn clean && mvn package
aws cloudformation package --template-file template.yml --s3-bucket alessandrotestbucket --output-template-file out.yml
aws cloudformation deploy --template-file out.yml --stack-name pct-common-monitoring-stack --capabilities CAPABILITY_NAMED_IAM
