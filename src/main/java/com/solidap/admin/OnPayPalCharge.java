package com.solidap.admin;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.paypal.api.payments.*;
import com.paypal.base.rest.APIContext;
import com.paypal.base.rest.PayPalRESTException;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Personalization;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;


public class OnPayPalCharge implements HttpFunction {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(OnPayPalCharge.class.getName());
    private static final Firestore FIRESTORE = FirestoreOptions.getDefaultInstance().getService();

    @Override
    public void service(HttpRequest request, HttpResponse response)
            throws IOException {
        Properties prop = new Properties();

        try (InputStream input = new FileInputStream("src/main/resources/application.properties")) {
            prop.load(input);
        } catch (IOException ex) {
            logger.info("ERROR in file(): " + ex.getMessage());

        }

        if (request.getPath().equals(prop.getProperty("clientPath") + "/paypal/pay")) {
            pay(request,response,prop);
            return;
        }

        if (request.getPath().equals(prop.getProperty("clientPath") + "/paypal/process")) {
            process(request,response,prop);
            return;
        }
    }

    public void pay(HttpRequest request, HttpResponse response, Properties prop) throws IOException {
        JsonObject responseObject = new JsonObject();
        setCORS(request,response);

        JsonObject data = new JsonObject();
        JsonElement requestParsed = gson.fromJson(request.getReader(), JsonElement.class);

        if (requestParsed != null) {
            Amount amount = new Amount();
            amount.setCurrency(prop.getProperty("currency"));
            amount.setTotal(requestParsed.getAsJsonObject().get("data").getAsJsonObject().get("cart").getAsJsonObject().get("total").getAsString());

            Transaction transaction = new Transaction();
            transaction.setAmount(amount);
            transaction.setDescription(requestParsed.getAsJsonObject().get("data").getAsJsonObject().get("cartId").getAsString());
            transaction.setCustom(request.getHeaders().get("Origin").get(0));
            transaction.setNoteToPayee(requestParsed.getAsJsonObject().get("data").getAsJsonObject().get("uid").getAsString());

            List<Transaction> transactions = new ArrayList<Transaction>();
            transactions.add(transaction);

            Payer payer = new Payer();
            payer.setPaymentMethod("paypal");
            PayerInfo payerInfo = new PayerInfo();
            payer.setPayerInfo(payerInfo);

            Payment payment = new Payment();
            payment.setIntent("sale");
            payment.setPayer(payer);
            payment.setTransactions(transactions);

            RedirectUrls redirectUrls = new RedirectUrls();
            redirectUrls.setCancelUrl(request.getHeaders().get("Origin").get(0) + "/cart");
            redirectUrls.setReturnUrl( prop.getProperty("clientRootPath") + prop.getProperty("clientPath")  + "/paypal/process");
            payment.setRedirectUrls(redirectUrls);

            try {
                APIContext apiContext = new APIContext(prop.getProperty("payPalClientId") , prop.getProperty("payPalClientSecret") , prop.getProperty("mode"));
                Payment createdPayment = payment.create(apiContext);
                for (Links link : createdPayment.getLinks()) {
                    if (link.getRel().equalsIgnoreCase("approval_url")) {
                        data.addProperty("redirect" ,link.getHref() );
                        data.addProperty("code" ,200 );
                    }
                }
            } catch (PayPalRESTException e) {
                logger.info("PayPalRESTException(): " + e.getMessage());
            } catch (Exception ex) {
                logger.info("Exception(): " + ex.getMessage());
            }
            responseObject.add("data" , data);
        }

        BufferedWriter writer = response.getWriter();
        writer.write(responseObject.toString());

    }

    public void process(HttpRequest request, HttpResponse response, Properties prop) throws IOException {

        Payment payment = new Payment();
        String redirectURL = prop.getProperty("clientRootPath");
        payment.setId(request.getQueryParameters().get("paymentId").get(0));
        try {
            APIContext apiContext = new APIContext(prop.getProperty("payPalClientId") , prop.getProperty("payPalClientSecret"),prop.getProperty("mode") );
            PaymentExecution paymentExecution = new PaymentExecution();
            paymentExecution.setPayerId(request.getQueryParameters().get("PayerID").get(0));
            Payment createdPayment = payment.execute(apiContext,paymentExecution);

            //Custom is the project root URL
            redirectURL = createdPayment.getTransactions().get(0).getCustom() + "/cart";

            DocumentReference order = FIRESTORE.collection("customers")
                    .document("customers")
                    .collection(createdPayment.getTransactions().get(0).getNoteToPayee()).document("orders")
                    .collection("orders").document(createdPayment.getTransactions().get(0).getDescription());

            try {
                Map<String, Object> rootObject = new HashMap<>();
                Map<String, Object> paymentObject = new HashMap<>();
                Map<String, Object> tokenObject = new HashMap<>();
                tokenObject.put("created" , System.currentTimeMillis());
                paymentObject.put("token" ,tokenObject);
                rootObject.put("payment",paymentObject);
                order.collection("paypal").add(rootObject);

            } catch (Exception ex) {
                logger.info("Exception while updating status(): " + ex.getMessage());
            }
            try{

                Map <String ,Object > orderDetails = order.get().get().getData();
                Map <String ,Object > personalInfo = (Map<String, Object>) orderDetails.get("personalInfo");
                Map <String ,Object > address = (Map<String, Object>) orderDetails.get("address");
                Map <String ,Object > cart = (Map<String, Object>) orderDetails.get("cart");

                setPaidStatus(order , cart);

                SendGrid sg = new SendGrid(prop.getProperty("sendGridKey"));
                Email from = new Email(prop.getProperty("sendGridFrom"));
                Email to = new Email(personalInfo.get("email").toString());
                Email admin = new Email(prop.getProperty("email"));
                Mail mail = new Mail();
                mail.setFrom(from);
                Personalization dynamicData = new Personalization();
                dynamicData.addTo(to);
                dynamicData.addTo(admin);
                dynamicData.addDynamicTemplateData("cartId" , createdPayment.getTransactions().get(0).getDescription());
                dynamicData.addDynamicTemplateData("personalInfo" , personalInfo);
                dynamicData.addDynamicTemplateData("address" , address);
                dynamicData.addDynamicTemplateData("cart" , cart);
                mail.addPersonalization(dynamicData);
                mail.setTemplateId(prop.getProperty("sendGridConfirmationTemplateId"));
                Request emailRequest = new Request();
                try {
                    emailRequest.setMethod(Method.POST);
                    emailRequest.setEndpoint("mail/send");
                    emailRequest.setBody(mail.build());
                    Response emailResponse = sg.api(emailRequest);
                    logger.info("emailResponse.getStatusCode() --------> " + emailResponse.getStatusCode());
                    logger.info("emailResponse.getBody() --------> " + emailResponse.getBody());
                    logger.info("emailResponse.getHeaders() --------> " + emailResponse.getHeaders());
                } catch (IOException ex) {
                    logger.info("EMAIL ERROR --------> " + ex.getMessage());
                }
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE,e.getMessage());
            } catch (ExecutionException e) {
                logger.log(Level.SEVERE,e.getMessage());
            }
        } catch (PayPalRESTException e) {
            logger.info("PayPalRESTException(): " + e.getMessage());
        } catch (Exception ex) {
            logger.info("Exception(): " + ex.getMessage());
        }
        setRedirect(redirectURL,response);
    }

    public void setRedirect(String redirect,HttpResponse response) {
        // Set Redirect Headers
        HttpURLConnection.setFollowRedirects(true);
        response.appendHeader("Location", redirect);
        response.setStatusCode(302);
    }

    public void setPaidStatus(DocumentReference affectedDoc,Map<String, Object> cart) {
        try{
            long count = 0;
            double ordersTotal = 0;
            for (QueryDocumentSnapshot doc:affectedDoc.
                    getParent().getParent().getParent().get().get()
                    .getDocuments()) {
                if (doc.getId().equals("orders")) {
                    if (doc.get("orderCount") !=null) {
                        count = (long) doc.get("orderCount");
                    }
                    if (doc.get("ordersTotal") !=null) {
                        ordersTotal = (double) doc.get("ordersTotal");
                    }
                }
            }
            Map<String,Object> stats = new HashMap<String,Object>();
            stats.put("orderCount",count + 1);
            stats.put("ordersTotal",ordersTotal + Double.valueOf((String)cart.get("grandTotal")));
            affectedDoc.getParent().getParent().set(stats);
            affectedDoc.update("status", "paid");
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage());
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE, e.getMessage());
        }

    }

    public void setCORS(HttpRequest request,HttpResponse response) {
        // Set CORS headers
        //   Allows GETs from any origin with the Content-Type
        //   header and caches preflight response for 3600s
        response.appendHeader("Access-Control-Allow-Origin", "*");
        response.appendHeader("Access-Control-Allow-Headers", "*");
        response.setContentType("application/json");
        //response.appendHeader("Access-Control-Allow-Credentials", "true");
        //response.appendHeader("Access-Control-Allow-Methods", "GET,HEAD,OPTIONS,POST,PUT");
        //response.appendHeader("Access-Control-Allow-Headers", "Access-Control-Allow-Headers, Origin,Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");

        if ("OPTIONS".equals(request.getMethod())) {
            response.appendHeader("Access-Control-Allow-Methods", "POST");
            response.appendHeader("Access-Control-Allow-Headers", "Content-Type");
            response.appendHeader("Access-Control-Max-Age", "3600");
            response.setStatusCode(HttpURLConnection.HTTP_NO_CONTENT);
            return;
        }
    }
}