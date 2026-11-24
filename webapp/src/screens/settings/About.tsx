import { createMemo, Show } from 'solid-js'
import { Bug01Icon, Clock01Icon, Copy01Icon, File01Icon, Idea01Icon, LegalDocument01Icon, Mail01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { t } from '../../i18n'
import { showToast } from '../../ui/Toast'
import { copyText, EMAIL, featureUrl, inboxUrl, issueUrl, mailto } from '../../ui/feedback'
import { feedbackFacts, openFeedback } from '../../ui/FeedbackForm'
import { ActionRow, LinkRow } from '../../ui/SettingsParts'
import { AboutNews } from '../SettingsFind'
import { LICENCES } from './shared'
import { VersionFooter } from '../../ui/VersionFooter'

/**
 * Report a problem and Request a feature (WB11, HX64). With the build's inbox address, each opens
 * the form in the page (ui/FeedbackForm.tsx) over the draft kept on this device; without one, each
 * is a link to GitHub's prefilled page, so the rows work before the inbox is deployed and in a
 * fork. An e-mail row and Copy details stay for a viewer with neither GitHub nor the form. Nothing
 * here sends anything: the form's own confirm step does, and shows what goes first.
 */
function Feedback() {
    const facts = createMemo(() => feedbackFacts())
    const form_ = () => inboxUrl() !== null
    const copy = async () => {
        try {
            await navigator.clipboard.writeText(copyText(facts()))
            showToast({ kind: 'success', text: t('player.details_copied') })
        } catch {
            // No clipboard: the facts are in the row under its title.
        }
    }
    return (
        <div class="set-card" data-feedback-rows>
            <Show
                when={form_()}
                fallback={
                    <>
                        <LinkRow row="report_problem" href={issueUrl(facts())} icon={Bug01Icon} title={t('settings.report_problem')} subtitle={t('settings.report_problem_detail')} />
                        <LinkRow row="request_feature" href={featureUrl()} icon={Idea01Icon} title={t('web.request_feature')} subtitle={t('web.request_feature_detail')} />
                    </>
                }
            >
                <ActionRow row="report_problem" icon={Bug01Icon} title={t('settings.report_problem')} subtitle={t('settings.report_problem_detail_inbox')} chevron onClick={(b) => openFeedback('problem', b)} />
                <ActionRow row="request_feature" icon={Idea01Icon} title={t('web.request_feature')} subtitle={t('settings.request_feature_detail')} chevron onClick={(b) => openFeedback('feature', b)} />
            </Show>
            <LinkRow href={mailto(facts(), 'problem')} icon={Mail01Icon} title={t('feedback.email')} subtitle={t('feedback.email_detail', { email: EMAIL })} sameTab />
            <Show when={typeof navigator !== 'undefined' && !!navigator.clipboard?.writeText}>
                <ActionRow icon={Copy01Icon} title={t('player.copy_details')} subtitle={copyText(facts()).split('\n').slice(1).join(', ')} onClick={() => void copy()} />
            </Show>
        </div>
    )
}

/** Help (WP-78, the apps' Help group): what is new, the reports and the way to write to the developer. */
export function Help() {
    return (
        <>
            <div class="set-card">
                <AboutNews variant="row" icon={StarIcon} changelogIcon={Clock01Icon} />
            </div>
            <Feedback />
        </>
    )
}

export function About() {
    return (
        <>
            {/* The masthead: the version, the build and the update state at the top, a press copies them (WP-104). */}
            <div class="set-version set-masthead">
                <VersionFooter look="line" />
            </div>
            <div class="set-card">
                <LinkRow row="notices" href={LICENCES} icon={File01Icon} title={t('about.notices_title')} subtitle={t('about.notices_detail')} />
                <LinkRow row="legal" href="https://tmplayer.org/legal" icon={LegalDocument01Icon} title={t('login.legal')} subtitle={t('auth.unofficial')} />
                <div class="set-block">
                    <p class="muted small">{t('about.licence_line')}</p>
                    <a class="link" href={LICENCES} target="_blank" rel="noopener">{t('about.read')}</a>
                </div>
            </div>
        </>
    )
}
