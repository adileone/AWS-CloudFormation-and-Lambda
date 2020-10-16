package org.wipo.lambda;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.HttpMethod;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.CloudWatchLogsEvent;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.GetLogEventsRequest;
import com.amazonaws.services.logs.model.GetLogEventsResult;
import com.amazonaws.services.logs.model.OutputLogEvent;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.util.Base64;

/**
 * Lambda function to manage CloudWatch Log notifications
 * 
 * @author Alessandro Di Leone
 *
 */

public class ManageLogEvents implements RequestHandler<CloudWatchLogsEvent, String> {

	private AmazonSimpleEmailService sesClient;
	private AWSLogs logsClient;
	private LambdaLogger logger;
	private AWSSimpleSystemsManagement ssmClient;
	private AWSSecurityTokenService stsClient;
	private AmazonS3 s3Client;

	public ManageLogEvents() {

		logsClient = AWSLogsClientBuilder.defaultClient();
		ssmClient = AWSSimpleSystemsManagementClientBuilder.defaultClient();

		s3Client = AmazonS3ClientBuilder.standard().withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
				.withRegion("eu-central-1").build();

		stsClient = AWSSecurityTokenServiceClientBuilder.standard()
				.withCredentials(DefaultAWSCredentialsProviderChain.getInstance()).withRegion("eu-central-1").build();

		String crossAccountRoleArn = System.getenv("CrossAccountRoleARN");
		String environment = System.getenv("Environment");

		if (environment.equals("dev")) {
			sesClient = AmazonSimpleEmailServiceClientBuilder.standard().withRegion(Regions.EU_CENTRAL_1).build();
		} else {
			sesClient = AmazonSimpleEmailServiceClientBuilder.standard()
					.withCredentials(
							new AWSStaticCredentialsProvider(CommonUtils.getSTSsession(crossAccountRoleArn, stsClient)))
					.withRegion(Regions.EU_CENTRAL_1).build();
		}
	}

	@Override
	public String handleRequest(CloudWatchLogsEvent input, Context context) {

		logger = context.getLogger();
		logger.log("\nEntering " + this.getClass().getSimpleName() + " handler \n");

		String region = System.getenv("Region");
		if (region == null) {
			throw new RuntimeException("Undefined env variable Region, cannot proceed.");
		}

		String s3BucketDestination = System.getenv("S3Bucket");
		if (s3BucketDestination == null) {
			throw new RuntimeException("Undefined env variable S3Bucket, cannot proceed.");
		}

		String env = System.getenv("Environment");
		if (env == null) {
			throw new RuntimeException("Undefined env variable Environment, cannot proceed.");
		}

		String data = input.getAwsLogs().getData();

		try {

			JSONObject event = new JSONObject(unzip(data));

			// logDescription
			JSONArray logEvents = event.getJSONArray("logEvents");
			JSONObject logJson = logEvents.getJSONObject(0);
			String id = logJson.getString("id");
			logger.log("CWLog received: " + id);
			Long timestampJson = logJson.getLong("timestamp");
			String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(timestampJson);
			String message = logJson.getString("message");

			String logGroup = event.getString("logGroup");
			String logStream = event.getString("logStream");
			String logGroupLink = "https://" + region + ".console.aws.amazon.com/cloudwatch/home?region=" + region
					+ "#logStream:group=" + logGroup;
			String logStreamLink = "https://" + region + ".console.aws.amazon.com/cloudwatch/home?region=" + region
					+ "#logEventViewer:group=" + logGroup + ";stream=" + logStream;

			JSONArray subFilters = event.getJSONArray("subscriptionFilters");
			String sf = subFilters.getString(0);
			String subject = "Alert: " + sf;

			String sender = CommonUtils.getSender("logs",logGroup, ssmClient);
			ArrayList<String> recipients = CommonUtils.getRecipients("logs",logGroup, ssmClient);

			// String taskId = shipLogs(s3BucketDestination, logGroup, logStream);
			// String destinationPrefix = "logs-export/"+logGroup;
			//
			// String objectKey = destinationPrefix+"/"+taskId+"/"+logStream.replace("/",
			// "-")+"/000000.gz";

			String fileName = shipLogs(s3BucketDestination, logGroup, logStream, timestampJson);
			String preSignedUrl = generateSelfSignedUrl(s3BucketDestination, fileName + ".txt");

			String text_body = "An ERROR log was added to the following logGroup: " + logGroup + "\n\n" + "logStream: "
					+ logStream + "\n" + "id: " + id + "\n" + "timestamp: " + timestamp + "\n" + "message: " + message
					+ ".\n" + "\nPlease follow this link to access the related logGroup:" + logGroupLink
					+ "\nPlease follow this link to access the related logStream:" + logStreamLink;

			String html_body = "<html>" + "<body>" + "<h2>An ERROR log was added to the following logGroup: " + logGroup
					+ "</h2>"
					+ "<p style=\"font-size:80%\"> Please open this message in your browser in order to navigate to the log stream."
					+ "<br>Please note that the DownloadLogs link will expire after 60minutes from now. </p>"
					+ "<br><strong>log:</strong> " + message + "<br>" + "<br>logStream: " + logStream + "<br>" + "id: "
					+ id + "<br>" + "timestamp: " + timestamp + "<br>" + "<br></br>"
					+ "<table width=\"100%\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\">" + "<tr>" + "<td>"
					+ "<table border=\"0\" cellspacing=\"0\" cellpadding=\"0\">" + "<tr>"
					+ "<td align=\"center\" style=\"border-radius: 3px;\" bgcolor=\"#e9703e\"><a href=" + logGroupLink
					+ " target=\"_blank\" style=\"font-size: 16px; font-family: Helvetica, Arial, sans-serif; color: #ffffff; text-decoration: none; text-decoration: none;border-radius: 3px; padding: 12px 18px; border: 1px solid #e9703e; display: inline-block;\">Go to LogGroup</a></td>"
					+ "<td align=\"center\" style=\"border-radius: 3px;\" bgcolor=\"#e9703e\"><a href=" + logStreamLink
					+ " target=\"_blank\" style=\"font-size: 16px; font-family: Helvetica, Arial, sans-serif; color: #ffffff; text-decoration: none; text-decoration: none;border-radius: 3px; padding: 12px 18px; border: 1px solid #e9703e; display: inline-block;\">Go to LogStream</a></td>"
					+ "<td align=\"center\" style=\"border-radius: 3px;\" bgcolor=\"#e9703e\"><a href=" + preSignedUrl
					+ " target=\"_blank\" style=\"font-size: 16px; font-family: Helvetica, Arial, sans-serif; color: #ffffff; text-decoration: none; text-decoration: none;border-radius: 3px; padding: 12px 18px; border: 1px solid #e9703e; display: inline-block;\">Download Logs</a></td>"
					+ "</tr>" + "</table>" + "</td>" + "</tr>" + "</table>" + "</body>" + "<footer>"
					+ "<p><small>This email was sent with"
					+ "<a href='https://aws.amazon.com/ses/'> Amazon SES</a> using the"
					+ "<a href='https://aws.amazon.com/sdk-for-java/'> AWS SDK for Java</a>.</small></p>" + "</footer>"
					+ "</html>";

			return CommonUtils.sendEmail(sender, recipients, subject, html_body, text_body, sesClient);

		} catch (Exception e1) {
			return e1.getMessage();
		}
	}

	public String generateSelfSignedUrl(String bucketName, String objectKey) {

		try {
			// Set the presigned URL to expire after 20 mins.
			java.util.Date expiration = new java.util.Date();
			long expTimeMillis = expiration.getTime();
			expTimeMillis += 1000 * 60 * 60;
			expiration.setTime(expTimeMillis);

			// Generate the presigned URL.
			logger.log("Generating pre-signed URL.");
			GeneratePresignedUrlRequest generatePresignedUrlRequest = new GeneratePresignedUrlRequest(bucketName,
					objectKey).withMethod(HttpMethod.GET).withExpiration(expiration);
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

	public String shipLogs(String s3BucketName, String logGroup, String logStream, Long timestamp) throws IOException {

		// long unixTimeFROM = (System.currentTimeMillis()-30000);
		// long unixTimeTO = System.currentTimeMillis();

		// long unixTimeFROM = Long.parseLong("1597489299000");
		// long unixTimeTO = (System.currentTimeMillis()-30000);
		//
		// GetLogEventsRequest req = new GetLogEventsRequest(logGroup, logStream)
		// .withStartTime(timestamp-30000)
		// .withEndTime(timestamp);

		File file = File.createTempFile(logGroup, ".txt", new File("/tmp"));

		FileWriter w = new FileWriter(file);

		List<OutputLogEvent> result = new ArrayList<>();
		String nextToken = null;
		String previousToken = null;
		GetLogEventsResult response;

		try {
			Thread.sleep(60000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		long startTime = System.currentTimeMillis() - 600000;
		long endTime = System.currentTimeMillis();

		logger.log("From: " + String.valueOf(startTime) + " To: " + String.valueOf(endTime));

		do {

			GetLogEventsRequest request = new GetLogEventsRequest(logGroup, logStream).withStartFromHead(true)
					.withStartTime(startTime).withEndTime(endTime);
			if (nextToken != null)
				request = request.withNextToken(nextToken);

			response = logsClient.getLogEvents(request);
			result.addAll(response.getEvents());

			previousToken = nextToken;
			nextToken = response.getNextBackwardToken();

			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

		} while (previousToken == nextToken);

		for (OutputLogEvent e : result) {
			w.write(e.getMessage());
			w.write('\n');
		}

		w.close();

		String fileName = logGroup + "." + String.valueOf(timestamp);
		s3Client.putObject(s3BucketName, fileName + ".txt", file);
		file.delete();

		return fileName;

		// CreateExportTaskRequest createExportTaskRequest = new
		// CreateExportTaskRequest()
		// .withDestination(s3BucketName)
		// .withDestinationPrefix("logs-export/"+logGroup)
		// .withLogGroupName(logGroup)
		// .withLogStreamNamePrefix(logStream)
		// .withFrom(unixTimeFROM)
		// .withTo(unixTimeTO);
		//
		// CreateExportTaskResult exportTask =
		// logsClient.createExportTask(createExportTaskRequest);
		// logger.log("ExportTaskId: "+exportTask.getTaskId());
		//
		// DescribeExportTasksRequest detr = new
		// DescribeExportTasksRequest().withTaskId(exportTask.getTaskId());
		// String resp =
		// logsClient.describeExportTasks(detr).getExportTasks().get(0).toString();
		// logger.log(resp);

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
		while ((line = bufferedReader.readLine()) != null) {
			output.append(line);
		}
		return output.toString();

	}

	public static boolean isZipped(final byte[] compressed) {
		return (compressed[0] == (byte) (GZIPInputStream.GZIP_MAGIC))
				&& (compressed[1] == (byte) (GZIPInputStream.GZIP_MAGIC >> 8));
	}

}