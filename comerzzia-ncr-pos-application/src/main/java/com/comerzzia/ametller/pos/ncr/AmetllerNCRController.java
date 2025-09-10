package com.comerzzia.ametller.pos.ncr;

import java.io.IOException;
import java.lang.reflect.Method;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.messages.AmetllerNCRMessageFactory;
import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;

/**
 * Custom NCRController that uses {@link AmetllerNCRMessageFactory} so the
 * system can understand the additional messages used in the 25% discount flow
 * such as {@code ExitAssistMode}.
 */
@Service("NCRController")
@Primary
public class AmetllerNCRController extends NCRController {

    @Override
    public BasicNCRMessage readNCRMessage() throws IOException {
        try {
            Method readMethod = NCRController.class.getDeclaredMethod("readMessage");
            readMethod.setAccessible(true);
            String message = (String) readMethod.invoke(this);
            if (message == null) {
                return null;
            }
            return AmetllerNCRMessageFactory.createFromString(message);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Error reading message", e);
        }
    }
}

