# Bash tab-completion for `z` and its short-name functions. Sourced by .zenv/activate.
#   z <TAB>          -> subcommands           zrun <TAB>      -> services
#   z run <TAB>      -> services              zfeature <TAB>  -> new | build-preloaded
#   z feature <TAB>  -> new | build-preloaded z build <TAB>   -> phases
# Handles both forms: for `z` the subcommand is $1; for a short name (zrun/zfeature/...)
# the command word itself is the subcommand (basename minus the leading z).
_z_complete() {
    local cur base sub argstart
    cur="${COMP_WORDS[COMP_CWORD]}"
    base="${COMP_WORDS[0]}"

    if [[ "$base" == "z" ]]; then
        if (( COMP_CWORD == 1 )); then
            COMPREPLY=( $(compgen -W "run exec up down pull log restart status build feature shared create-zenv fresh-install help" -- "$cur") )
            return
        fi
        sub="${COMP_WORDS[1]}"; argstart=2
    else
        sub="${base#z}"; argstart=1        # zrun->run, zfeature->feature, zbuild->build, ...
    fi

    local services="base compile db solr httpd tomcat blast mailpit jenkins ncbiload fail2ban certbot elasticsearch filebeat metricbeat kibana"
    case "$sub" in
        run|exec)                  COMPREPLY=( $(compgen -W "$services -u" -- "$cur") ) ;;
        up|down|pull|log|restart)  COMPREPLY=( $(compgen -W "$services" -- "$cur") ) ;;
        build)                     COMPREPLY=( $(compgen -W "configure load-db load-solr deploy-jenkins deploy all --build --test" -- "$cur") ) ;;
        shared)                    COMPREPLY=( $(compgen -W "up down status --tag --rm-data" -- "$cur") ) ;;
        feature)
            if (( COMP_CWORD == argstart )); then
                COMPREPLY=( $(compgen -W "new ls rm build-preloaded" -- "$cur") )
            else
                case "${COMP_WORDS[argstart]}" in
                    new)             COMPREPLY=( $(compgen -W "--up --hosts --shared-db --no-app --no-caches --no-node --tag --ip --ip-base --base --branch" -- "$cur") ) ;;
                    rm)              COMPREPLY=( $(compgen -W "--force" -- "$cur") ) ;;
                    build-preloaded) COMPREPLY=( $(compgen -W "--tag --app --caches --keep-tarballs --project" -- "$cur") ) ;;
                esac
            fi ;;
    esac
}
complete -F _z_complete z zrun zexec zup zdown zpull zlog zrestart zstatus zhelp zfeature zbuild
