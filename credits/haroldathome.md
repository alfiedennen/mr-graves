# haroldathome

This repository accompanies the *Mr Graves* piece at
[haroldathome.com/mr-graves](https://haroldathome.com/mr-graves).

haroldathome is a long-running set of domestic experiments wired into
a 1930s house in Hastings, England, by Alfie Dennen — with his partner
Elena, an AI named Harold Graves who used to live there, and a small
server in the cellar.

The wake word *Mr Graves* is the formal address — what the household
calls the AI when summoning him via the on-device Android listener
this repo implements. The casual *Hey Harold* is handled by a sibling
stack on ESP32 speakers (microWakeWord; trainer at
[alfiedennen/microwakeword-trainer](https://github.com/alfiedennen/microwakeword-trainer)).

The formal-vs-casual address split is deliberate. *Harold* gets said
in passing at the breakfast table — a wake word listening for it
would fire on every conversational mention. *Mr Graves* lives in a
quieter zone of the language; it's rarely said by accident.

Each piece of the studio is documented at [haroldathome.com](https://haroldathome.com).
Selected pieces are also extracted into their own public repos under
[github.com/alfiedennen](https://github.com/alfiedennen) — this is one
of those.

If you build something using this, I'd love to see it. Get in touch
via [alfie@haroldathome.com](mailto:alfie@haroldathome.com).
