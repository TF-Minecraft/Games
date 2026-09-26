package net.tfminecraft.games.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

/** Supplies Paper's registries and isolates server state between test classes. */
public final class ServerExtension implements BeforeAllCallback, AfterAllCallback, TestExecutionExceptionHandler {
    @Override
    public void beforeAll(ExtensionContext context) {
        MockBukkit.mock();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        MockBukkit.unmock();
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable error) throws Throwable {
        if (error instanceof UnimplementedOperationException) {
            throw new AssertionError("Unsupported MockBukkit operation: supply an explicit external API mock", error);
        }
        throw error;
    }
}
