package com.solidap.admin;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.paypal.api.payments.*;
import com.paypal.base.rest.APIContext;
import com.paypal.base.rest.PayPalRESTException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;


public class OnPayPalCharge implements HttpFunction {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(OnPayPalCharge.class.getName());
    private static final Firestore FIRESTORE = FirestoreOptions.getDefaultInstance().getService();
    String clientId = "AQ7dB8TnwEsm-A0ffir8NyoLytl5DnHLcctHSIGLZl-6MrAmeStSF2KW4_u1DX8NXm4cm64S4gDH8xkb";
    String clientSecret = "EHUe9I3N2Qix8y9zLd92FOTCzFMoQ7nYRrU19crYMERsbB6CsmDvcfftFOe26seDm-yKvnz6wIDAzIHq";
    String clientPath ="/my-doo-7c70c/us-central1";
    String clientRootPath ="https://doodemo.com";
    String currency ="CAD";
    String mode = "sandbox";

    @Override
    public void service(HttpRequest request, HttpResponse response)
            throws IOException {

        if (request.getPath().equals(clientPath + "/paypal/pay")) {
            pay(request,response);
            return;
        }

        if (request.getPath().equals(clientPath + "/paypal/process")) {
            process(request,response);
            return;
        }
    }

    public void pay(HttpRequest request, HttpResponse response) throws IOException {
        JsonObject responseObject = new JsonObject();
        setCORS(request,response);

        JsonObject data = new JsonObject();
        JsonElement requestParsed = gson.fromJson(request.getReader(), JsonElement.class);

        if (requestParsed != null) {
            Amount amount = new Amount();
            amount.setCurrency(currency);
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
            redirectUrls.setReturnUrl(clientRootPath + clientPath + "/paypal/process");
            payment.setRedirectUrls(redirectUrls);

            try {
                APIContext apiContext = new APIContext(clientId, clientSecret, mode);
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

    public void process(HttpRequest request, HttpResponse response) throws IOException {

        Payment payment = new Payment();
        String redirectURL = clientRootPath;
        payment.setId(request.getQueryParameters().get("paymentId").get(0));
            try {
                APIContext apiContext = new APIContext(clientId, clientSecret, mode);
                PaymentExecution paymentExecution = new PaymentExecution();
                paymentExecution.setPayerId(request.getQueryParameters().get("PayerID").get(0));
                Payment createdPayment = payment.execute(apiContext,paymentExecution);

                redirectURL = createdPayment.getTransactions().get(0).getCustom() + "/cart";

                /*JsonElement token = JsonParser.
                        parseString(gson.toJson(FIRESTORE.document(affectedDoc).get().get().get("payment"))).getAsJsonObject().get("token");

                JsonElement personalInfo = JsonParser.
                        parseString(gson.toJson(FIRESTORE.document(affectedDoc).getParent().getParent().get().get().get("personalInfo")));

                JsonElement cart = JsonParser.
                        parseString(gson.toJson(FIRESTORE.document(affectedDoc).getParent().getParent().get().get().get("cart")));*/

                DocumentReference order = FIRESTORE.collection("customers")
                        .document("customers")
                        .collection(createdPayment.getTransactions().get(0).getNoteToPayee()).document("orders")
                        .collection("orders").document(createdPayment.getTransactions().get(0).getDescription());

                try {
                    order.update("status" , "paid");
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
                logger.info("STATUS: " + order.get().get().get("status"));
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