package org.wipo.lambda.printpayload;

import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.ecs.model.DescribeServicesRequest;
import com.amazonaws.services.ecs.model.DescribeServicesResult;
import com.amazonaws.services.ecs.model.DesiredStatus;
import com.amazonaws.services.ecs.model.ListTasksRequest;
import com.amazonaws.services.ecs.model.ListTasksResult;
import com.amazonaws.services.ecs.model.Service;
import com.amazonaws.services.ecs.model.StopTaskRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;

/**
 * Lambda function to restart ECS tasks Input : SNSEvent payload = ServiceName and ClusterName
 * 
 * @author ansingha, Alessandro Di Leone
 *
 */

public class LambdaFunctionHandler implements RequestHandler<SNSEvent, String> {

    @Override
    public String handleRequest(SNSEvent event, Context context) {
    	
		LambdaLogger logger = context.getLogger();
    	
        String message = event.getRecords().get(0).getSNS().getMessage();        
        JSONObject msg = new JSONObject(message);
		JSONObject trigger = msg.getJSONObject("Trigger");
		JSONArray dimensions = trigger.getJSONArray("Dimensions");		
		JSONObject clusterJson = dimensions.getJSONObject(1);
		JSONObject serviceJson = dimensions.getJSONObject(0);
		
		String clusterName=clusterJson.getString("value");
		String serviceName=serviceJson.getString("value");
        
		logger.log("Entering " + this.getClass().getSimpleName() + " handler");

		logger.log("\nStarting restart of "+serviceName+" service in "+clusterName+" cluster");

		try {
			AmazonECS client = AmazonECSClientBuilder.standard().build();

			Service service = getService(clusterName, serviceName, client);

			String status = service.getStatus();
			String taskDefinition = service.getTaskDefinition();
			Integer desiredCount = service.getDesiredCount();
			logger.log("\nService status: " + status);
			logger.log("\nTask definition: " + taskDefinition);
			logger.log("\nDesired tasks count: " + desiredCount);

			List<String> runningTasksArn = getTasksArns(clusterName, serviceName, client, DesiredStatus.RUNNING);
			logger.log("\nService is running "+runningTasksArn.size()+" tasks\n");
			stopTasks(logger, clusterName, client, runningTasksArn);
			logger.log("\nCompleted service restart");
		} catch (Exception e) {
			logger.log("\nERROR: " + e.getMessage() +"\n");
			return "ERROR";
		}

		return "SUCCESS";
	}

	private Service getService(String cluster, String service, AmazonECS client) {
		DescribeServicesRequest request = new DescribeServicesRequest();
		request.setCluster(cluster);
		request.setServices(Arrays.asList(service));
		DescribeServicesResult result = client.describeServices(request);
		return result.getServices().get(0);
	}
	
	private List<String> getTasksArns(String cluster, String service, AmazonECS client, DesiredStatus desiredStatus) {
		ListTasksRequest tasksRequest = new ListTasksRequest();
		tasksRequest.setCluster(cluster);
		//tasksRequest.setFamily(service);
		tasksRequest.setServiceName(service);
		tasksRequest.setDesiredStatus(desiredStatus);
		ListTasksResult tasksResult = null;
		tasksResult = client.listTasks(tasksRequest);
		return tasksResult.getTaskArns();
	}
	
	/*private RunTaskResult runTasks(LambdaLogger logger, String cluster, AmazonECS client, Service service,
			Integer count) {
		logger.log("\nSTARTING task..");
		RunTaskRequest runTaskRequest = new RunTaskRequest();
		runTaskRequest.setCluster(cluster);
		runTaskRequest.setLaunchType(service.getLaunchType());
		runTaskRequest.setTaskDefinition(service.getTaskDefinition());
		runTaskRequest.setNetworkConfiguration(service.getNetworkConfiguration());
		runTaskRequest.setStartedBy("RestartApp Lambda");
		runTaskRequest.setCount(count);
		RunTaskResult runTaskResult = client.runTask(runTaskRequest);
		logger.log("\nSTART result = " + runTaskResult.toString());
		client.runTask(runTaskRequest);
		return runTaskResult;
	}*/

	private void stopTasks(LambdaLogger logger, String cluster, AmazonECS client, List<String> taskArns) {
		StopTaskRequest stopTaskRequest = new StopTaskRequest();
		stopTaskRequest.setCluster(cluster);
		stopTaskRequest.setReason("RestartApp Lambda");
		for (String task : taskArns) {
			logger.log("\nStopping task: " + task);
			stopTaskRequest.setTask(task);
			client.stopTask(stopTaskRequest);
		}
	}
}
