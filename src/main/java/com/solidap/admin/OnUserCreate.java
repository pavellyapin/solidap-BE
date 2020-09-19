package com.solidap.admin;

import com.google.cloud.functions.Context;
import com.google.cloud.functions.RawBackgroundFunction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

import java.io.IOException;
import java.util.logging.Logger;

public class OnUserCreate implements RawBackgroundFunction {
    private static final Logger logger = Logger.getLogger(OnUserCreate.class.getName());

    // Use GSON (https://github.com/google/gson) to parse JSON content.
    private static final Gson gson = new Gson();

    @Override
    public void accept(String json, Context context) {

        JsonObject body = gson.fromJson(json, JsonObject.class);
        SendGrid sg = new SendGrid("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");

        Email from = new Email("Pavel <hello@splidoo.com>");
        String subject = "Sending with SendGrid is Fun";
        Email to = new Email(body.get("email").getAsString());
        Content content = new Content("text/plain", "and easy to do anywhere, even with Java");
        Mail mail = new Mail(from, subject, to, content);
        mail.setTemplateId("d-cf0424195e3947f3a4132621e38463cb");
        Request request = new Request();
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sg.api(request);
            System.out.println(response.getStatusCode());
            System.out.println(response.getBody());
            System.out.println(response.getHeaders());
        } catch (IOException ex) {
            //throw ex;
        }


        if (body != null && body.has("uid")) {
            logger.info("Function triggered by change to user: " + body.get("uid").getAsString());
        }

        if (body != null && body.has("metadata")) {
            JsonObject metadata = body.get("metadata").getAsJsonObject();
            logger.info("Created at: " + metadata.get("createdAt").getAsString());
        }

        if (body != null && body.has("email")) {
            logger.info("Email: " + body.get("email").getAsString());
        }
    }
}