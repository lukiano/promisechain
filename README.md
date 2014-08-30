# PromiseChain

Enqueues promises (CompletableFuture in Java 8) for a given key in a FIFO order so that access to shared code is restricted to one thread at a time if an EXCLUSIVE lock is used or unrestristed access as long as SHARED locks are used.

*Requires Java 8.*

## Lock types
The lock types are the standard ReadWrite:

* EXCLUSIVE: only the piece of code will be executed 
* SHARED: the piece of code may be executed concurrently with other codes with SHARED locks.
 
No locks are actually used, but a concatenation of Futures.
 
## Usage
 
<code>
    private A someCode() { ... }
    
    private CompletableFuture<A> someAsyncCode() {
        // Asynchronously call someCode()
        Executor executor = ...
        CompletableFuture<A> result = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                result.complete(someCode());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    public void main() {
        PromiseChain<String> promiseChain = PromiseChain.create(); // Assume keys are strings
        
        // Since EXCLUSIVE, someAsyncCode() will be called after all previous blocks of code in the same key
        // are finished, and "future" will be then fulfilled.
        CompletableFuture<A> future = promiseChain.enqueueOn(key, PromiseChain.Type.EXCLUSIVE, () -> someAsyncCode());
        
        future.thenAccept(System.out::println); // Print the value once it's complete. 
    }    
</code>