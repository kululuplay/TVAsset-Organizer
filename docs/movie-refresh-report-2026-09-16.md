# Movie refresh completion and partial-failure reporting

The explicit dashboard refresh previously reported success from the live-channel
result and only refreshed the movie category index. The Movies screen separately
stopped its full sweep on the first category error. These paths could therefore
leave newer movie content absent while the user believed the catalogue was fresh.

Both explicit refresh paths now use the same sequential movie sweep. Every
visible category is force-fetched; individual failures do not skip subsequent
categories. Existing snapshot/transaction checks still preserve cached data on a
failed fetch. A failed category index remains a failure in the final report, but
known cached categories are still attempted. Hidden categories remain excluded.
Background sync retains its lightweight behaviour.

The Movies screen displays an incomplete-refresh state with category names and a
remote-accessible details button. Its scrollable dialog lists every failed
category with a translated reason; index errors are labelled separately. Retry
forces a fresh sweep, including previously loaded categories. Focus changes do
not interrupt an explicit full refresh.

The dashboard awaits actual movie contents and checks the whole report before
showing its updated message. A failed movie sweep shows the same named error
list. Cancellation propagates and cannot produce a success message. Provider
URLs, account credentials and raw exception bodies are not displayed.

Regression tests exercise a failed middle category with a successful later
category, multiple failures, index failure with cached categories, a blocked last
response, partial completion, retry, empty accepted responses and cancellation.
Physical customer-device reproduction and production publication are separate
from source/unit-test validation; no customer-specific root cause is claimed.
