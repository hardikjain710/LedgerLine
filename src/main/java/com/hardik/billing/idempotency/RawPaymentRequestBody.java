package com.hardik.billing.idempotency;

import com.hardik.billing.payment.PaymentController;
import com.hardik.billing.payment.dto.CreatePaymentRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

@ControllerAdvice(assignableTypes = PaymentController.class)
public class RawPaymentRequestBody extends RequestBodyAdviceAdapter {

    public static final String ATTRIBUTE = "rawPaymentRequestBody";

    private final HttpServletRequest request;

    public RawPaymentRequestBody(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType.equals(CreatePaymentRequest.class);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] bytes = message.getBody().readAllBytes();
        request.setAttribute(ATTRIBUTE, bytes);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }
}
