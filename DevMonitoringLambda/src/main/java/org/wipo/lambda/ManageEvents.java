package org.wipo.lambda;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.zip.GZIPInputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.HttpMethod;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.CreateExportTaskRequest;
import com.amazonaws.services.logs.model.CreateExportTaskResult;
import com.amazonaws.services.logs.model.DescribeExportTasksRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.Credentials;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterResult;
import com.amazonaws.util.Base64; 

/**
 * Lambda function to manage notifications
 * 
 * @author Alessandro Di Leone
 *
 */

@SuppressWarnings("unchecked")
public class ManageEvents implements RequestHandler<Object, String> {

	private AmazonSimpleEmailService sesClient;
	private AWSLogs logsClient; 
	private LambdaLogger logger;
	private AWSSimpleSystemsManagement ssmClient;
	private AWSSecurityTokenService stsClient;
	private AmazonS3 s3Client;

	public ManageEvents() {

		logsClient = AWSLogsClientBuilder.defaultClient();
		ssmClient = AWSSimpleSystemsManagementClientBuilder.defaultClient();

		s3Client = AmazonS3ClientBuilder.standard()
				.withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
				.withRegion("eu-central-1")
				.build();

		stsClient = AWSSecurityTokenServiceClientBuilder.standard()
				.withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
				.withRegion("eu-central-1")
				.build();

		String crossAccountRoleArn = System.getenv("CrossAccountRoleARN");

		if (crossAccountRoleArn==null) {
			sesClient = AmazonSimpleEmailServiceClientBuilder.standard().withRegion(Regions.EU_CENTRAL_1).build();
		}else {
			sesClient = AmazonSimpleEmailServiceClientBuilder.standard()
					.withCredentials(new AWSStaticCredentialsProvider(getSTSsession(crossAccountRoleArn)))
					.withRegion(Regions.EU_CENTRAL_1).build();}
	}


	@Override
	public String handleRequest(Object input, Context context) {

		logger = context.getLogger();
		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		LinkedHashMap<String,Object> requestsLHM =  (LinkedHashMap<String, Object>) input;

		//Is SNSEvent
		try {

			ArrayList<LinkedHashMap<String,Object> > records = (ArrayList<LinkedHashMap<String,Object> > ) requestsLHM.get("Records");

			//event0
			LinkedHashMap<String,Object> rec0 = (LinkedHashMap<String,Object>) records.get(0);
			String eventType = (String) rec0.get("EventSource");
			logger.log("SNSEvent received");

			//sns
			LinkedHashMap<String,Object> sns = (LinkedHashMap<String,Object>) rec0.get("Sns");
			String message = (String) sns.get("Message");
			String subject = (String) sns.get("Subject");
			String timestamp = (String) sns.get("Timestamp");

			//message
			JSONObject msg = new JSONObject(message);
			String alarmName = msg.getString("AlarmName");
			String alarmDescription = msg.getString("AlarmDescription");
			String newStateValue = msg.getString("NewStateValue");
			String newStateReason = msg.getString("NewStateReason");
			String stateChangeTime = msg.getString("StateChangeTime"); 
			String AWSAccountID=msg.getString("AWSAccountId");

			//trigger
			JSONObject trigger = msg.getJSONObject("Trigger");
			String metricName = trigger.getString("MetricName");
			String comparisonOperator = trigger.getString("ComparisonOperator");

			String[] thresholdArray = newStateReason.split(" ");
			String threshold = thresholdArray[12]; 

			//dimensions
			JSONArray dimensions = trigger.getJSONArray("Dimensions");
			JSONObject dimTG = dimensions.getJSONObject(0);
			String TargetGroup=dimTG.getString("value");

			String sender = getSender(alarmName);
			ArrayList<String> recipients = getRecipients(alarmName);

			String TGurl="https://eu-central-1.console.aws.amazon.com/ec2/v2/home?region=eu-central-1#TargetGroups:search=arn:aws:elasticloadbalancing:eu-central-1:"+AWSAccountID+":"+TargetGroup;

			String text_body = "A "+eventType+" event was received: "+subject+".\n\n"
					+ "Timestamp: "+timestamp+"\n\n"
					+ "AlarmName: "+alarmName+"\n"
					+ "AlarmDescription: "+alarmDescription+"\nNewStateValue: "+newStateValue+"\nNewStateReason: "+newStateReason+"\nStateChangeTime: "+stateChangeTime+"\n\n"
					+ "Trigger:\n\tMetricName: "+metricName+"\n\tComparisonOperator: "+comparisonOperator+"\n\tThreshold: "+threshold+" \n\n" 
					+ "*Please select an action:*";


			String html_body = "<html>"
					+ "<head></head>"
					+ "<body>"
					+ "<h1>A "+eventType+" event was received: "+subject+".</h1>"
					+ "<p>Please open this message in your browser in order to navigate to the target group. <p>"
					+ "<br>Timestamp: "+timestamp+"<br>"
					+ "<br><strong>AlarmName:</strong> "+alarmName+"<br>"
					+ "<br>AlarmDescription: "+alarmDescription+"<br>NewStateValue: "+newStateValue+"<br>NewStateReason: "+newStateReason+"<br>StateChangeTime: "+stateChangeTime+"<br></br>"
					+ "<p>Trigger:</p>"
					+ "<ul>"
					+ "<li>MetricName: "+metricName+"</li>"
					+ "<li>ComparisonOperator: "+comparisonOperator+"</li>"
					+ "<li>Threshold: "+threshold+"</li>"
					+ "</ul>"
					+ "<div>"
					+ "<br> <a href=\""+TGurl+"\" style=\"margin:5px;background-color:#EB7035;float:left;border:1px solid #EB7035;border-radius:3px;color:#ffffff;display:inline-block;font-family:sans-serif;font-size:12px;line-height:44px;text-align:center;text-decoration:none;width:100px;-webkit-text-size-adjust:none;mso-hide:all;\">Go to TargetGroup</a>"
					+ "<br></br>"
					+ "</div>"
					+ "</body>"
					+ "<footer>"
					+ "<p><br></br><br></br><small>This email was sent with"
					+ "<a href='https://aws.amazon.com/ses/'> Amazon SES</a> using the"
					+ "<a href='https://aws.amazon.com/sdk-for-java/'> AWS SDK for Java</a>.</small></p>"
					+ "</footer>"
					+ "</html>"; 


			sendEmail(sender, recipients, subject, html_body, text_body);

			return "DONE";

		} 

		//Is CWLog
		catch (Exception e) {

			LinkedHashMap<String,Object> myRequests = (LinkedHashMap<String,Object>) requestsLHM.get("awslogs");
			String data = (String) myRequests.get("data");
			logger.log("CWLog received");

			try {

				JSONObject event = new JSONObject(unzip(data));

				//logDescription 
				JSONArray logEvents = event.getJSONArray("logEvents");
				JSONObject logJson = logEvents.getJSONObject(0);
				String id=logJson.getString("id");
				Long timestampJson = logJson.getLong("timestamp");
				String timestamp=new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(timestampJson);
				String message= logJson.getString("message");

				String logGroup = event.getString("logGroup");
				String logStream=event.getString("logStream");
				String logGroupLink = "https://eu-central-1.console.aws.amazon.com/cloudwatch/home?region=eu-central-1#logStream:group="+logGroup;
				String logStreamLink = "https://eu-central-1.console.aws.amazon.com/cloudwatch/home?region=eu-central-1#logEventViewer:group="+logGroup+";stream="+logStream;

				JSONArray subFilters = event.getJSONArray("subscriptionFilters");
				String sf = subFilters.getString(0);
				String subject ="Alert: "+sf;

				String sender = getSender(logGroup);
				ArrayList<String> recipients = getRecipients(logGroup);


				String s3BucketDestination = System.getenv("S3Bucket");
				if (s3BucketDestination==null) {
					throw new RuntimeException("Undefined env variable S3Bucket, cannot proceed.");
				}

				String taskId = shipLogs(s3BucketDestination, logGroup, logStream);
				String destinationPrefix = "logs-export/"+logGroup;				

				String objectKey = destinationPrefix+"/"+taskId+"/"+logStream.replace("/", "-")+"/000000.gz";
				String preSignedUrl = generateSelfSignedUrl(s3BucketDestination, objectKey);

				String text_body = "An ERROR log was added to the following logGroup: "+logGroup+"\n\n"
						+ "logStream: "+logStream+"\n"
						+ "id: "+id+"\n"
						+ "timestamp: "+timestamp+"\n"
						+ "message: "+message+".\n"
						+ "\nPlease follow this link to access the related logGroup:"+logGroupLink
						+ "\nPlease follow this link to access the related logStream:"+logStreamLink;

				String html_body = "<html>"
						+ "<body>"
						+ "<h2>An ERROR log was added to the following logGroup: "+logGroup+"</h2>"
						+ "<p style=\"font-size:80%\"> Please open this message in your browser in order to navigate to the log stream."
						+ "<br>Please note that the DownloadLogs link will expire after 20minutes from now. </p>"
						+ "<br><strong>log:</strong> "+message+"<br>"
						+ "<br>logStream: "+logStream+"<br>"
						+ "id: "+id+"<br>"
						+ "timestamp: "+timestamp+"<br>"
						+ "<br></br>"
						+ "<table width=\"100%\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\">"
						+ "<tr>"
						+ "<td>"
						+ "<table border=\"0\" cellspacing=\"0\" cellpadding=\"0\">"
						+ "<tr>"
						+ "<td align=\"center\" style=\"border-radius: 3px;\" bgcolor=\"#e9703e\"><a href="+logGroupLink+" target=\"_blank\" style=\"font-size: 16px; font-family: Helvetica, Arial, sans-serif; color: #ffffff; text-decoration: none; text-decoration: none;border-radius: 3px; padding: 12px 18px; border: 1px solid #e9703e; display: inline-block;\">Go to LogGroup</a></td>"
						+ "<td align=\"center\" style=\"border-radius: 3px;\" bgcolor=\"#e9703e\"><a href="+logStreamLink+" target=\"_blank\" style=\"font-size: 16px; font-family: Helvetica, Arial, sans-serif; color: #ffffff; text-decoration: none; text-decoration: none;border-radius: 3px; padding: 12px 18px; border: 1px solid #e9703e; display: inline-block;\">Go to LogStream</a></td>"
						+ "<td align=\"center\" style=\"border-radius: 3px;\" bgcolor=\"#e9703e\"><a href="+preSignedUrl+" target=\"_blank\" style=\"font-size: 16px; font-family: Helvetica, Arial, sans-serif; color: #ffffff; text-decoration: none; text-decoration: none;border-radius: 3px; padding: 12px 18px; border: 1px solid #e9703e; display: inline-block;\">Download Logs</a></td>"
						+ "</tr>"
						+ "</table>"
						+ "</td>"
						+ "</tr>"
						+ "</table>"						
						+ "</body>"
						+ "<footer>"
						+ "<p><small>This email was sent with"
						+ "<a href='https://aws.amazon.com/ses/'> Amazon SES</a> using the"
						+ "<a href='https://aws.amazon.com/sdk-for-java/'> AWS SDK for Java</a>.</small></p>"
						+ "</footer>"
						+ "</html>";

				sendEmail(sender, recipients, subject, html_body, text_body);

				return "Successful execution";

			} catch (Exception e1) {
				e1.printStackTrace();
				return(e1.toString());
			} 
		}       	
	}


	public String generateSelfSignedUrl(String bucketName, String objectKey) {

		try {
			// Set the presigned URL to expire after one hour.
			java.util.Date expiration = new java.util.Date();
			long expTimeMillis = expiration.getTime();
			expTimeMillis += 1000 * 60 * 60;
			expiration.setTime(expTimeMillis);

			// Generate the presigned URL.
			logger.log("Generating pre-signed URL.");
			GeneratePresignedUrlRequest generatePresignedUrlRequest =
					new GeneratePresignedUrlRequest(bucketName, objectKey)
					.withMethod(HttpMethod.GET)
					.withExpiration(expiration);
			URL url = s3Client.generatePresignedUrl(generatePresignedUrlRequest);

			logger.log("Pre-Signed URL: " + url.toString());

			return url.toString();

		} catch (AmazonServiceException e) {
			// The call was transmitted successfully, but Amazon S3 couldn't process 
			// it, so it returned an error response.
			e.printStackTrace();
			return e.toString();
		} catch (SdkClientException e) {
			// Amazon S3 couldn't be contacted for a response, or the client
			// couldn't parse the response from Amazon S3.
			e.printStackTrace();
			return e.toString();
		}

	}


	public String shipLogs(String s3BucketName, String logGroup, String logStream) {

		long unixTimeTO = System.currentTimeMillis();  
		long unixTimeFROM = (System.currentTimeMillis()-30000);

		CreateExportTaskRequest createExportTaskRequest = new CreateExportTaskRequest()
				.withDestination(s3BucketName)
				.withDestinationPrefix("logs-export/"+logGroup)
				.withLogGroupName(logGroup)
				.withLogStreamNamePrefix(logStream)
				.withFrom(unixTimeFROM)
				.withTo(unixTimeTO);

		CreateExportTaskResult exportTask = logsClient.createExportTask(createExportTaskRequest);
		logger.log("ExportTaskId: "+exportTask.getTaskId());

		DescribeExportTasksRequest detr = new DescribeExportTasksRequest().withTaskId(exportTask.getTaskId());		
		String resp = logsClient.describeExportTasks(detr).getExportTasks().get(0).toString();
		logger.log(resp);

		return exportTask.getTaskId();
	}


	public BasicSessionCredentials getSTSsession(String crossAccountRoleArn) {

		AssumeRoleRequest roleRequest = new AssumeRoleRequest()
				.withRoleArn(crossAccountRoleArn)
				.withRoleSessionName("cross-account-session");

		Credentials sessionCredentials = stsClient.assumeRole(roleRequest).getCredentials();

		BasicSessionCredentials awsCredentials = new BasicSessionCredentials(
				sessionCredentials.getAccessKeyId(),
				sessionCredentials.getSecretAccessKey(),
				sessionCredentials.getSessionToken());

		return awsCredentials;
	}


	public ArrayList<String> getRecipients(String projID) {
		String[] splitted = projID.split("-", 2); 
		String project=splitted[0];			
		String paramListOfRecipientsName="/"+project+"/ListOfRecipients";			
		GetParameterResult listOfRecipientsResult = getParameter(paramListOfRecipientsName);
		String recipientsList = listOfRecipientsResult.getParameter().getValue();
		String[] mails = recipientsList.split(",");
		ArrayList<String> recipients = new ArrayList<String>();
		for (String s : mails) {recipients.add(s);}
		return recipients;
	}


	public String getSender(String projID) {
		String[] splitted = projID.split("-", 2); 
		String project=splitted[0];
		String paramSenderName="/"+project+"/alarms/sender";
		GetParameterResult senderResult = getParameter(paramSenderName);
		String sender = senderResult.getParameter().getValue();
		return sender;
	}


	public String unzip(String zip) throws Exception {
		String encoded = zip;
		byte[] compressed = Base64.decode(encoded);

		if ((compressed == null) || (compressed.length == 0)) {
			throw new IllegalArgumentException("Cannot unzip null or empty bytes");
		}
		if (!isZipped(compressed)) {
			System.out.println(compressed);
		}

		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(compressed);
		GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream);
		InputStreamReader inputStreamReader = new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8);
		BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
		StringBuilder output = new StringBuilder();
		String line;
		while((line = bufferedReader.readLine()) != null){
			output.append(line);}
		return output.toString();

	}


	public static boolean isZipped(final byte[] compressed) {
		return (compressed[0] == (byte) (GZIPInputStream.GZIP_MAGIC)) && (compressed[1] == (byte) (GZIPInputStream.GZIP_MAGIC >> 8));
	}


	public GetParameterResult getParameter(String parameterName) {

		GetParameterRequest request = new GetParameterRequest();
		request.setName(parameterName);
		request.setWithDecryption(false);
		return ssmClient.getParameter(request); 
	}


	public void sendEmail(String from, ArrayList<String> to, String subject, String html_body, String text_body) throws IOException {

		try {

			SendEmailRequest request = new SendEmailRequest()
					.withDestination(
							new Destination().withToAddresses(to))
					.withMessage(new Message()
							.withBody(new Body()
									.withHtml(new Content()
											.withCharset("UTF-8").withData(html_body))
									.withText(new Content()
											.withCharset("UTF-8").withData(text_body)))
							.withSubject(new Content()
									.withCharset("UTF-8").withData(subject)))
					.withSource(from);

			sesClient.sendEmail(request);
			logger.log("Email sent!");
		} catch (Exception ex) {
			logger.log("The email was not sent: " + ex.getMessage());
		}
	}

}