package com.example.JunitPlayGround.junit5playground.initializers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.BinaryFile;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import org.apache.logging.log4j.util.Strings;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.regex.Pattern.compile;

public class YlluxBodyTransformer extends ResponseDefinitionTransformer {

    private static final Pattern RESPONSE_FIELD_PATTERN = Pattern.compile("\\$\\(.*?\\)"); // e.g. $(id)
    private static final Pattern REGEX_URL_PATTERN = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*?)>");// e.g. (?<id>.*?)
    private static final Pattern RANDOM_INTEGER_PATTERN = Pattern.compile("!RandomInteger");

    private static final String TRANSFORMER_NAME = "body-transformer";
    private static final String URL_REGEX = "urlRegex";
    private ObjectMapper objectMapper;


    public YlluxBodyTransformer() {
        objectMapper = new ObjectMapper();
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

    @Override
    public String getName() {
        return TRANSFORMER_NAME;
    }

    @Override
    public ResponseDefinition transform(Request request, ResponseDefinition responseDefinition, FileSource fileSource, Parameters transformerParameters) {
        if (this.hasEmptyResponseBody(responseDefinition)) {
            return responseDefinition;
        }

        String urlRegex = Optional
                .of(transformerParameters.getString(URL_REGEX))
                .orElseThrow(() -> new RuntimeException("urlRegex cannot be null"));

        Map<String, String> requestValues = extractFieldsFromRequest(request);
        Map<String, String> urlValues = extractParametersFromURL(request.getUrl(), urlRegex);

        Map<String, String> context = mergeMaps(requestValues, urlValues);

        String responseBody = getResponseBody(responseDefinition, fileSource);
        String modifiedResponseBody = replaceResponseFieldsWithValuesFromContext(responseBody, context);

        return ResponseDefinitionBuilder
                .like(responseDefinition)
                .but()
                .withBody(modifiedResponseBody)
                .build();
    }

    private Map<String, String> mergeMaps(Map<String, String> map1, Map<String, String> map2) {
        return Stream.of(map1, map2)
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));
    }

    private boolean hasEmptyResponseBody(ResponseDefinition responseDefinition) {
        return responseDefinition.getBody() == null && responseDefinition.getBodyFileName() == null;
    }

    public Map<String, String> extractFieldsFromRequest(Request request) {
        String requestBody = request.getBodyAsString();
        if (!Strings.isEmpty(requestBody)) {
            try {
                return objectMapper.readValue(requestBody, Map.class);
            } catch (JsonProcessingException e) {
                System.out.println("Request is empty");
            }
        }
        return new HashMap<>();
    }

    private static Map<String, String> extractParametersFromURL(String url, String urlRegex) {
        List<String> keys = extractKeysFromRegexURL(urlRegex);
        List<String> values = extractValuesFormURL(url, urlRegex);

        System.out.println("Keys " + keys);
        System.out.println("Values " + values);
        Map<String, String> map = new HashMap<>();
        if (keys.size() == values.size()) {
            for (int i = 0; i < keys.size(); i++) {
                map.put(keys.get(i), values.get(i));
            }
        } else {
            System.out.println("Values dont match");
        }
        return map;
    }

    private static List<String> extractValuesFormURL(String url, String urlRegex) {
        Matcher matcher = compile(urlRegex)
                .matcher(url);

        matcher.matches();

        List<String> values = new ArrayList<>();
        for (int i = 1; i < matcher.groupCount() + 1; i++) {
            values.add(matcher.group(i));
            System.out.println(matcher.group(i));
        }

        return values;
    }

    private static List<String> extractKeysFromRegexURL(String urlRegex) {
        return REGEX_URL_PATTERN
                .matcher(urlRegex)
                .results()
                .map(matchResult -> {
                    System.out.println(matchResult.group(1));
                    return matchResult.group(1);

                })
                .collect(Collectors.toList());
    }

    private String getResponseBody(ResponseDefinition responseDefinition, FileSource fileSource) {
        String body;
        if (responseDefinition.getBody() != null) {
            body = responseDefinition.getBody();
        } else {
            BinaryFile binaryFile = fileSource.getBinaryFileNamed(responseDefinition.getBodyFileName());
            body = new String(binaryFile.readContents(), StandardCharsets.UTF_8);
        }
        return body;
    }

    private String replaceResponseFieldsWithValuesFromContext(String responseBodyRaw, Map<String, String> context) {
        String modifiedResponseBody = responseBodyRaw;
        List<String> responseFields = RESPONSE_FIELD_PATTERN.matcher(responseBodyRaw)
                .results()
                .map(MatchResult::group)
                .collect(Collectors.toList());

        for (String regexField : responseFields) {
            if (!context.containsKey(regexField.substring(2, regexField.length() - 1))) {
                modifiedResponseBody = modifiedResponseBody.replace(regexField, "null");
            } else
                modifiedResponseBody = modifiedResponseBody.replace(regexField, getValueForContext(regexField, context));
        }

        return modifiedResponseBody;
    }

    private String getValueForContext(String field, Map<String, String> transformerContext) {
        if (RANDOM_INTEGER_PATTERN.matcher(field).find()) {
            return String.valueOf((new Random()).nextInt(2147483647));
        } else {
            String fieldName = field.substring(2, field.length() - 1);
            return transformerContext.get(fieldName);
        }
    }

}