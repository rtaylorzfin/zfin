# The review proxy: stock nginx-proxy, with the discovery variable renamed.
#
# nginx-proxy routes by reading one environment variable off every container the Docker socket
# shows it -- stock, that is VIRTUAL_HOST. On a host where ANOTHER nginx-proxy watches the same
# socket (ZFIN's ngproxy on the shared VMs), a routed stack carrying VIRTUAL_HOST is seen by
# both. The upstream cannot reach it -- no shared network -- and its EXACT vhost outranks our
# wildcard, so the stack's own hostname resolves to an upstream with no members:
#
#   [error] no live upstreams while connecting to upstream,
#           server: zfin-99999.coral.zfin.org, upstream: "https://zfin-99999..."
#
# Reachability filtering does not save us: that drops the `server` lines, after the vhost
# already exists. nginx-proxy has no per-container opt-out label either. So we read a name the
# stock template never looks at, set by docker-compose.review-member.yml.
#
# ONE token in the 1045-line template selects it, which is why this is a one-word derivative
# rather than a fork. The template stays the pinned image's own -- FROM keeps it current
# without vendoring 1045 lines into git.
#
#   range $hostname, $containers := groupByMulti $globals.containers "Env.VIRTUAL_HOST" ","
#
# The `test` is the point of doing this at build time. `sed` exits 0 when it matches nothing, so
# without the guard an upgrade that moves or renames that token would produce an image that
# builds clean, starts clean, and routes nothing. Fail here instead, where someone is watching.
#
# Keep ZFIN_VIRTUAL_HOST in step with StackConfig.REVIEW_VHOST_ENV, which is what writes it onto
# a routed stack. Pinned by tag on purpose: an nginx-proxy upgrade is a `z review up` away
# (it always rebuilds), never a surprise from a re-pulled floating tag.
FROM nginxproxy/nginx-proxy:1.7-alpine

RUN test "$(grep -c '"Env.VIRTUAL_HOST"' /app/nginx.tmpl)" = 1 \
 && sed -i 's/"Env.VIRTUAL_HOST"/"Env.ZFIN_VIRTUAL_HOST"/' /app/nginx.tmpl \
 && grep -q '"Env.ZFIN_VIRTUAL_HOST"' /app/nginx.tmpl

# The "no such stack" page. Static, tracked, identical on every host and never generated -- so
# it belongs in the image, not in a bind mount from the repo. nginx-proxy's generated FALLBACK
# server renders this when a hostname matches no routed stack; the template emits `error_page`
# only there, so a real stack's genuine 502 still shows its own error.
COPY 50x.html /usr/share/nginx/html/errors/50x.html
