package org.wipo.lambda;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.MetricDatum;
import com.amazonaws.services.cloudwatch.model.PutMetricDataRequest;
import com.amazonaws.services.cloudwatch.model.PutMetricDataResult;
import com.amazonaws.services.cloudwatch.model.StandardUnit;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

/**
 * Lambda function to perform healthchecks based on DynamoDB table entries
 * 
 * @author Alessandro Di Leone
 *
 */

public class Healthcheck implements RequestHandler<ScheduledEvent, String> {

	private LambdaLogger logger;
	private AmazonDynamoDB dynamoDbClient;
	private AmazonCloudWatch cloudWatchClient;
	//	private AWSLambda lambdaClient;

	public Healthcheck() {
		dynamoDbClient = AmazonDynamoDBClientBuilder.standard().build();
		cloudWatchClient = AmazonCloudWatchClientBuilder.defaultClient();
		//		lambdaClient = AWSLambdaClientBuilder.defaultClient();
	}

	//	private String checkSecurityGroupSet(String functionName, String tableName) {
	//
	//		GetFunctionRequest req = new GetFunctionRequest().withFunctionName(functionName);
	//
	//		GetFunctionResult res = lambdaClient.getFunction(req);
	//
	//		List<String> currentSecGroups = res.getConfiguration().getVpcConfig().getSecurityGroupIds();
	//		List<String> scannedSecGroups = new LinkedList<String>();
	//
	//		ScanRequest scanRequest = new ScanRequest(tableName);
	//		ScanResult result = dynamoDbClient.scan(scanRequest);
	//
	//		for (Map<String, AttributeValue> map : result.getItems()) {
	//
	//			String[] secGroups = map.get("security group").getS().split(",");
	//			for (String s : secGroups) {
	//				scannedSecGroups.add(s);
	//			}
	//			;
	//		}
	//
	//		Set<String> currentSet = new HashSet<String>(currentSecGroups);
	//		Set<String> scannedSet = new HashSet<String>(scannedSecGroups);
	//
	//		if (currentSet.equals(scannedSet)) {
	//			return "ok";
	//		} else {
	//			return scannedSet.toString();
	//		}
	//	}

	@Override
	public String handleRequest(ScheduledEvent input, Context context) {

		logger = context.getLogger();
		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		String tableName = System.getenv("TableName");
		if (tableName == null) {
			throw new RuntimeException("Undefined env variable TableName, cannot proceed.");
		}

		//		String secGroupStatus = checkSecurityGroupSet(context.getFunctionName(), tableName);
		//		if (secGroupStatus != "ok") {
		//
		//			logger.log("\nPlease synchronize the SecurityGroup IDs list from the parameter SecGroupIds");
		//			logger.log("\nRequested value should be:" + secGroupStatus);
		//			logger.log("\nNot all endpoints may have been successfully verified");
		//		}

		logger.log("\nScanning table " + tableName);

		ScanRequest scanRequest = new ScanRequest(tableName);
		ScanResult result = dynamoDbClient.scan(scanRequest);

		if (result.getItems().isEmpty()) {
			return "No healthcheck to perform. DynamoDB table is empty.";
		}

		for (Map<String, AttributeValue> map : result.getItems()) {

			String url = map.get("endpoint").getS();
			String type = map.get("type").getS();

			logger.log("\ntype: " + type);

			if (type.equals("service")) {

				int statusCode;
				try {
					statusCode = ping(url);
				} catch (IllegalArgumentException | SecurityException | IOException | InterruptedException e) {
					logger.log("\nhttp client failed to ping " + url + " : ");
					e.printStackTrace();
					continue;	
				}

				logger.log("\n" + url + " response code: " + statusCode);

				Pattern p = Pattern.compile("[45]0[0-9]");
				Matcher m = p.matcher(String.valueOf(statusCode));

				if (m.find()) {

					String project = map.get("project").getS();
					String service = map.get("service").getS();

					Dimension projectDim = new Dimension().withName("project").withValue(project);
					Dimension serviceDim = new Dimension().withName("service").withValue(service); 


					MetricDatum datum = new MetricDatum().withMetricName("4xx_5xx").withUnit(StandardUnit.None)
							.withValue((double) 1).withDimensions(projectDim, serviceDim);

					PutMetricDataRequest request = new PutMetricDataRequest().withNamespace("PCT-COMMON/Healthcheck")
							.withMetricData(datum);

					@SuppressWarnings("unused")
					PutMetricDataResult response = cloudWatchClient.putMetricData(request);

				}

			} else {

				int statusCode;
				try {
					statusCode = ping(url);
				} catch (IllegalArgumentException | SecurityException | IOException | InterruptedException e) {
					logger.log("\nhttp client failed : ");
					e.printStackTrace();
					return "ERROR";
				}

				logger.log("\n" + url + " response code: " + statusCode);
			}

		}
		return "ok";
	}

	public int ping(String url) throws IOException, InterruptedException, IllegalArgumentException, SecurityException {

		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
		logger.log("\npinging " + request.uri().toString());
		HttpClient client = HttpClient.newBuilder().build();
		HttpResponse<String> response = null;

		response = client.send(request, HttpResponse.BodyHandlers.ofString());

		return response.statusCode();
	}
}
