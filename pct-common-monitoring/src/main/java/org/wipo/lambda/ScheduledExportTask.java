package org.wipo.lambda;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.CreateExportTaskRequest;
import com.amazonaws.services.logs.model.CreateExportTaskResult;
import com.amazonaws.services.logs.model.DescribeLogGroupsRequest;
import com.amazonaws.services.logs.model.DescribeLogGroupsResult;
import com.amazonaws.services.logs.model.ListTagsLogGroupRequest;
import com.amazonaws.services.logs.model.ListTagsLogGroupResult;
import com.amazonaws.services.logs.model.LogGroup;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterResult;
import com.amazonaws.services.simplesystemsmanagement.model.ParameterNotFoundException;
import com.amazonaws.services.simplesystemsmanagement.model.PutParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.PutParameterResult;

/**
 * Scheduled Lambda to automate exportTask from CloudWatch to S3
 * 
 * @author Alessandro Di Leone
 *
 */

public class ScheduledExportTask implements RequestHandler<ScheduledEvent, String> {

	private AWSLogs logsClient; 
	private LambdaLogger logger;
	private AWSSimpleSystemsManagement ssmClient;

	public ScheduledExportTask() {
		logsClient = AWSLogsClientBuilder.defaultClient();
		ssmClient = AWSSimpleSystemsManagementClientBuilder.defaultClient();
	}

	@Override
	public String handleRequest(ScheduledEvent event, Context context) {

		logger = context.getLogger();

		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		String s3Bucket = System.getenv("S3Bucket");
		if (s3Bucket==null) {
			throw new RuntimeException("Undefined env variable S3Bucket, cannot proceed.");
		}

		logger.log("S3Bucket: "+s3Bucket);

		DescribeLogGroupsRequest logGroupsRequest = new DescribeLogGroupsRequest();
		DescribeLogGroupsResult logGroupsResult = logsClient.describeLogGroups(logGroupsRequest);

		List<LogGroup> logGroups = new ArrayList<LogGroup>();

		logGroups.addAll(logGroupsResult.getLogGroups());

		try {Thread.sleep(1000);} 
		catch (InterruptedException e) {e.printStackTrace();}

		while (logGroupsResult.getNextToken()!=null) {

			DescribeLogGroupsRequest logGroupsRequestNextToken = new DescribeLogGroupsRequest().withNextToken(logGroupsResult.getNextToken());
			DescribeLogGroupsResult response = logsClient.describeLogGroups(logGroupsRequestNextToken);
			logGroups.addAll(response.getLogGroups());

			try {Thread.sleep(1000);} 
			catch (InterruptedException e) {e.printStackTrace();}

			logGroupsResult=response;
		}

		logger.log("\nNumber of Log Groups detected: " + logGroups.size());

		List<String> logGroupsToExport = new ArrayList<String>();

		for (LogGroup lg : logGroups) {

			try {Thread.sleep(1000);} 
			catch (InterruptedException e) {e.printStackTrace();}

			String logGroupName = lg.getLogGroupName();
			ListTagsLogGroupRequest listTagsLogGroupRequest = new ListTagsLogGroupRequest().withLogGroupName(logGroupName); 
			ListTagsLogGroupResult response = logsClient.listTagsLogGroup(listTagsLogGroupRequest);
			Map<String, String> logGroupTags = response.getTags();

			if (logGroupTags.containsKey("ExportToS3") && logGroupTags.get("ExportToS3").equalsIgnoreCase("true")) {
				logGroupsToExport.add(logGroupName);
			}
		}

		for (String logGroupName : logGroupsToExport) {

			String ssmParameterName = "/log-exporter-last-export/"+logGroupName;
			String ssmValue=null;

			try {
				GetParameterRequest ssmRequest= new GetParameterRequest().withName(ssmParameterName);
				GetParameterResult ssmResponse=ssmClient.getParameter(ssmRequest);

				ssmValue=ssmResponse.getParameter().getValue();

			} catch (ParameterNotFoundException e) {
				ssmValue="0";			
			}

			long exportToTime =System.currentTimeMillis();

			logger.log("\nExporting "+logGroupName+" to "+s3Bucket);

			logger.log("\nExportToTime :"+exportToTime+" ssmValue: "+ssmValue+" delta="+ String.valueOf(exportToTime - Long.parseLong(ssmValue)));

			//24hours
			long offset = (24 * 60 * 60 * 1000);

			if ((exportToTime - Long.parseLong(ssmValue)) < offset ){

				// Haven't been 24hrs from the last export of this log group
				logger.log("Skipped "+logGroupName+" until 24hrs from last export is completed");
				//				continue;
			}else{

				try {

					CreateExportTaskRequest createExportTaskRequest = new CreateExportTaskRequest()
							.withDestination(s3Bucket)
							.withDestinationPrefix("logsExport-"+logGroupName)
							.withLogGroupName(logGroupName)
							.withFrom(Long.parseLong(ssmValue))
							.withTo(exportToTime);

					CreateExportTaskResult exportTask = logsClient.createExportTask(createExportTaskRequest);
					logger.log("\nExportTaskId: "+exportTask.getTaskId());

					try {Thread.sleep(5000);} 
					catch (InterruptedException e) {e.printStackTrace();}				

				} catch (Exception e) {
					logger.log("\nError exporting "+logGroupName+" : "+ e.getMessage());
				}

				PutParameterRequest putParameterRequest = new PutParameterRequest().withName(ssmParameterName).withType("String").withValue(String.valueOf(exportToTime)).withOverwrite(true);
				@SuppressWarnings("unused")
				PutParameterResult putParameterResult = ssmClient.putParameter(putParameterRequest);

				logger.log("\nNew value for parameter "+ssmParameterName+" = "+ssmValue);
			}
		}

		return "ok";
	}
}