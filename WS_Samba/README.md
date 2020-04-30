## AWS-CloudFormation+EFS+SMB

EFS Shared FileSystem mounted by 3 tasks into an ECS cluster exposed by a NetworkLoadBalancer.
These 3 tasks are sharing by SMB the EFS mountpoint in order to integrate with Windows.
