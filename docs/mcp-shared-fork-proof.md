# Shared-fork live evidence proof

A shared fork's whole point — two authenticated principals reading and
writing the same belief state, each attributed by the server rather than by
what they claim — cannot be closed by a Clojure unit test alone when the
evidence has to cross an external channel: a second, independently
authenticated client, verified out of band (a chat transport, another
process, a human reading two terminals). Record that leg as an operational
receipt rather than simulating it.

1. Principal A asserts a unique sentence in the shared context, records its
   server-recorded `:creator`, and sends its returned handle out over the
   external channel as an evidence key.
2. Principal B receives that evidence key and checks that its external
   marker and any structured locator it carries agree.
3. Principal B independently decodes the locator to the same handle and
   dereferences it through B's own credential. The sentence, context,
   handle, and creator must match A's receipt.
4. Repeat B→A with a second unique assertion. Neither principal may borrow or
   receive the other's credential.

A per-principal-fork topology is the negative control: transport and decode
succeed, but dereference returns unknown because the principals do not share
belief state. A shared-fork pass must differ at that final dereference step.

This proves transport interoperability for trusted, co-located principals. It
does not prove hostile-writer isolation or mutation ownership; that security
work is deferred and is not covered here.
