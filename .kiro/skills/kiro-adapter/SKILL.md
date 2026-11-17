---
name: kiro-adapter
description: Maps the Cursor names in pstack, thermos, and cursor-team-kit skills to Kiro CLI. Load it before you follow any of those skills, or any skill that says Task, subagent_type, AskQuestion, .cursor/, or Cursor.
---

# Kiro adapter

pstack, thermos, and cursor-team-kit skills are written for Cursor. In Kiro CLI, read each Cursor name in them through the tables below. If a Cursor field has no row, drop it. Never pass a field or value the tool schema rejects.

## Tools

| Cursor | Kiro |
| --- | --- |
| `Task` | `invoke_sub_agent`. Calls made in the same turn run in parallel. |
| `AskQuestion` | Ask in chat with numbered options. |
| Open a todolist | Keep the list in your reply and update it at each phase. |
| Run another skill (`/name`, "the **name** skill", "load the `name` skill") | `load_resource` with `skill::<name>`. When you tell the user to type it, write `/<name>`. |
| `Grep`, `Glob` | `grep_search`, `file_search`. |
| `/loop` | Wait between checks with a timed shell command, such as `sleep 60`. |

## Subagents

`invoke_sub_agent` takes only `name`, `prompt`, `explanation`, and `contextFiles`. The agent file sets the model, effort, and tools.

| Cursor | Kiro |
| --- | --- |
| `subagent_type: "poteto-agent"` | `name: "poteto-agent"`. Put the absolute path of the `poteto-mode` `SKILL.md` in the prompt. |
| `subagent_type: "comment-sicko"` | `name: "comment-sicko"` |
| `subagent_type: "thermo-nuclear-review-subagent"` | `name: "thermo-nuclear-review-subagent"` |
| `subagent_type: "thermo-nuclear-code-quality-review-subagent"` | `name: "thermo-nuclear-code-quality-review-subagent"` |
| `generalPurpose`, `shell`, `explore`, any other `subagent_type` | `name: "general-task-execution"` |
| `model`, effort, a `-fast` slug | Drop. |
| `readonly: true` | The brief says "do not edit files", and you check `git status` afterward. |
| `run_in_background`, `is_background`, `environment: "local"` | Drop. |
| `environment: "cloud"`, a Cursor cloud agent | Run locally in its own `git worktree`. Name the branch or commit the worker checks out. |
| `resume` | Spawn a new agent and paste the earlier result into its brief. |
| `file_attachments` | `contextFiles`. |

## Models

The agent files set every model. A role that names `poteto-agent`, `comment-sicko`, or a thermo reviewer runs on that agent's model. Every other role, including review and design panels, runs on `general-task-execution`, which uses the parent chat model, so a panel has one model family. Say so once in the report. Skip `/setup-pstack`, `pstack-models.mdc`, and `pstack-models.md`.

## Paths

| Cursor | Kiro |
| --- | --- |
| `.cursor/skills/`, `~/.cursor/skills/`, `~/.cursor/plugins/` | `.kiro/skills/`, `~/.kiro/skills/` |
| `.cursor-plugin/plugin.json` version checks | Skip. One copy is installed. |
| `.cursor/rules/*.mdc`, always-applied rules | `.kiro/steering/*.md`, `~/.kiro/steering/*.md`, or `AGENTS.md` |
| Transcripts, `agent-transcripts/` | `~/.kiro/sessions/<hash>/sess_<id>/messages.jsonl`. `<hash>` is the first 16 hex characters of the SHA-256 of the workspace path. Subagent runs are in that session's `sub-executions/`. Read only this workspace's folder. |
| A link from a skill to `../../docs/...` or `../../README.md` | Not installed. Read it at `https://github.com/cursor/plugins/blob/main/pstack/<path>`. |

## Skills Kiro doesn't ship

- `create-skill`: use `skill-creator` when it is installed. Otherwise follow the Agent Skills format at agentskills.io.
- `deslop`, `control-cli`, and `control-ui` from cursor-team-kit are installed. The other cursor-team-kit skills are not.
- `setup-pstack`, `make-bot-ui`, `ketchup-video`, and `reflect` are not installed (`excluded_skills` in `kiro/install.sh`). When a skill links to one, tell the user it is not installed in Kiro, and don't follow the link.

## Permissions

A prompt for a tool call comes from the user's permission rules. Never try to write `~/.kiro/settings/` or `.kiro/settings/`. Kiro refuses those writes.

## Cursor-only features

Kiro has no Cursor Automations, Bugbot, Cursor cloud agents, Cursor canvases, or built-in PR tool. When a step needs one, put one line in your reply, `Skipped in Kiro: <step>. <reason>.`, then go on. A step that uses a forge command, such as `gh pr create`, is not a skip.
