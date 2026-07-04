# Bash tab-completion for the `z` umbrella command. Sourced by .zenv/activate (so it
# loads when you activate a stack). `complete -r z` on deactivate removes it.
#   z <TAB>            -> subcommands
#   z run <TAB>        -> services + flags (e.g. `z run co<TAB>` -> compile)
#   z build <TAB>      -> phases + --build/--test
#   z feature <TAB>    -> new | build-preloaded (then that subcommand's flags)
_z_complete() {
    local cur cword sub
    cur="${COMP_WORDS[COMP_CWORD]}"
    cword=$COMP_CWORD

    if (( cword == 1 )); then
        COMPREPLY=( $(compgen -W "run exec up down pull log restart status build feature help" -- "$cur") )
        return
    fi

    sub="${COMP_WORDS[1]}"
    # Compose services (static list -- fast; a dev rarely needs one not here).
    local services="base compile db solr httpd tomcat blast mailpit jenkins ncbiload fail2ban certbot elasticsearch filebeat metricbeat kibana"
    case "$sub" in
        run|exec)
            COMPREPLY=( $(compgen -W "$services -u -c" -- "$cur") ) ;;
        up|down|pull|log|restart)
            COMPREPLY=( $(compgen -W "$services" -- "$cur") ) ;;
        build)
            COMPREPLY=( $(compgen -W "configure load-db load-solr deploy-jenkins deploy all --build --test" -- "$cur") ) ;;
        feature)
            if (( cword == 2 )); then
                COMPREPLY=( $(compgen -W "new build-preloaded" -- "$cur") )
            else
                case "${COMP_WORDS[2]}" in
                    new)             COMPREPLY=( $(compgen -W "--up --hosts --tag --ip --ip-base --base --branch" -- "$cur") ) ;;
                    build-preloaded) COMPREPLY=( $(compgen -W "--tag --slim --keep-tarballs --project" -- "$cur") ) ;;
                esac
            fi ;;
    esac
}
complete -F _z_complete z
