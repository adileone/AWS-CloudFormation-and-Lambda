package org.wipo.lambda;

import java.io.IOException;
import java.util.ArrayList;

import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.Credentials;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterResult;

public class CommonUtils {

	public static BasicSessionCredentials getSTSsession(String crossAccountRoleArn, AWSSecurityTokenService stsClient) {

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

	public static String getParameter(String parameterName, AWSSimpleSystemsManagement ssmClient) {

		GetParameterRequest request = new GetParameterRequest();
		request.setName(parameterName);
		request.setWithDecryption(false);
		GetParameterResult listOfRecipientsResult  = ssmClient.getParameter(request); 
		return listOfRecipientsResult.getParameter().getValue();
	}


	public static ArrayList<String> getRecipients(String feature,String projID, AWSSimpleSystemsManagement ssmClient) {
		
		String[] splitted = projID.split("-", 2); 
		String project=splitted[0];			
		String paramName="/"+project+"/"+feature+"/"+"recipients";	
		
		String recipientsList = getParameter(paramName, ssmClient);

		String[] mails = recipientsList.split(",");
		ArrayList<String> recipients = new ArrayList<String>();
		for (String s : mails) {recipients.add(s);}
		return recipients;
	}


	public static String getSender(String feature, String projID, AWSSimpleSystemsManagement ssmClient) {
		String[] splitted = projID.split("-", 2); 
		String project=splitted[0];
		String paramSenderName="/"+project+"/"+feature+"/"+"sender";
		return getParameter(paramSenderName, ssmClient);		
	}

	public static String sendEmail(String from, ArrayList<String> to, String subject, String html_body, String text_body, AmazonSimpleEmailService sesClient) throws IOException {

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
			return "Email sent!";
		} catch (Exception ex) {
			return "The email was not sent: " + ex.getMessage();
		}
	}

}