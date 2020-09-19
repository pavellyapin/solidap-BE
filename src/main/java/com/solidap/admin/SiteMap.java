package com.solidap.admin;

import com.contentful.java.cma.CMAClient;
import com.contentful.java.cma.model.CMAArray;
import com.contentful.java.cma.model.CMAEntry;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


public class SiteMap implements HttpFunction {

    @Override
    public void service(HttpRequest request, HttpResponse response)
            throws IOException {


        CMAClient client = new CMAClient.Builder()
                .setAccessToken("CFPAT-UPSA6Duy1QECqF3EuHiQ9gPQCSrF4eXlqbPZvgG391E").setSpaceId("req6f5i83hv4")
                .build();

        try {
            DocumentBuilderFactory dbFactory =
                    DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.newDocument();

            // root element
            Element rootElement = doc.createElement("urlset");
            rootElement.setAttribute("xmlns" , "http://www.sitemaps.org/schemas/sitemap/0.9");
            doc.appendChild(rootElement);

            Map<String, String> pageQuery = new HashMap<String, String>();
            pageQuery.put("content_type" , "page");
            CMAArray<CMAEntry> sitePages = client.entries().fetchAll(pageQuery);
            for (CMAEntry page : sitePages.getItems()) {
                Element urlElement = doc.createElement("url");
                Element urlLoc = doc.createElement("loc");
                urlLoc.setTextContent("https://www.doodemo.com/page/" + page.getField("name" , "en-US").toString());
                urlElement.appendChild(urlLoc);
                rootElement.appendChild(urlElement);
            }

            Map<String, String> categoryQuery = new HashMap<String, String>();
            categoryQuery.put("content_type" , "category");
            CMAArray<CMAEntry> siteCategories = client.entries().fetchAll(categoryQuery);
            for (CMAEntry category : siteCategories.getItems()) {
                Element urlElement = doc.createElement("url");
                Element urlLoc = doc.createElement("loc");
                urlLoc.setTextContent("https://www.doodemo.com/cat/" + category.getField("name" , "en-US").toString());
                urlElement.appendChild(urlLoc);
                rootElement.appendChild(urlElement);
            }

            Map<String, String> productQuery = new HashMap<String, String>();
            productQuery.put("content_type" , "product");
            CMAArray<CMAEntry> siteProducts = client.entries().fetchAll(productQuery);
            for (CMAEntry product : siteProducts.getItems()) {
                Element urlElement = doc.createElement("url");
                Element urlLoc = doc.createElement("loc");
                urlLoc.setTextContent("https://www.doodemo.com/product/" + product.getId());
                urlElement.appendChild(urlLoc);
                rootElement.appendChild(urlElement);
            }

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            BufferedWriter writer = response.getWriter();

            StreamResult consoleResult = new StreamResult(writer);
            transformer.transform(source, consoleResult);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}