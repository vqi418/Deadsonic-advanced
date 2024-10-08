package org.airsonic.player.controller;

import com.google.common.collect.ImmutableMap;
import org.airsonic.player.command.CredentialsManagementCommand;
import org.airsonic.player.command.CredentialsManagementCommand.AdminControls;
import org.airsonic.player.command.CredentialsManagementCommand.CredentialsCommand;
import org.airsonic.player.domain.User;
import org.airsonic.player.domain.UserCredential.App;
import org.airsonic.player.security.PasswordEncoderConfig;
import org.airsonic.player.service.SecurityService;
import org.airsonic.player.service.SettingsService;
import org.airsonic.player.validator.CredentialsManagementValidators.CredentialCreateChecks;
import org.airsonic.player.validator.CredentialsManagementValidators.CredentialUpdateChecks;
import org.apache.commons.beanutils.BeanMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.groups.Default;

import java.security.Principal;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

@Controller
@RequestMapping({"/credentialsSettings", "/credentialsSettings.view"})
public class CredentialsManagementController {

    @Autowired
    private SecurityService securityService;

    @Autowired
    private SettingsService settingsService;

    private static final Map<String, String> ENCODER_ALIASES = ImmutableMap.of("noop", "plaintext", "legacynoop",
            "legacyplaintext (deprecated)", "legacyhex", "legacyhex (deprecated)");

    @GetMapping
    protected String displayForm() {
        return "credentialsSettings";
    }

    @ModelAttribute
    protected void displayForm(Authentication user, ModelMap map) {
        List<CredentialsCommand> creds = securityService.getCredentials(user.getName(), App.values())
                .parallelStream()
                .map(CredentialsCommand::fromUserCredential)
                .map(c -> {
                    if (c.getEncoder().startsWith("legacy")) {
                        c.addDisplayComment("migratecred");
                    }

                    if (PasswordEncoderConfig.OPENTEXT_ENCODERS.contains(c.getEncoder())) {
                        c.addDisplayComment("opentextcred");
                    }

                    if (PasswordEncoderConfig.DECODABLE_ENCODERS.contains(c.getEncoder())) {
                        c.addDisplayComment("decodablecred");
                    } else {
                        c.addDisplayComment("nondecodablecred");
                    }

                    return c;
                })
                .sorted(Comparator.comparing(CredentialsCommand::getCreated))
                .collect(Collectors.toList());

        User userInDb = securityService.getUserByName(user.getName());

        // for updates/deletes/read
        map.addAttribute("command", new CredentialsManagementCommand(creds));
        // for new creds
        map.addAttribute("newCreds", new CredentialsCommand());

        map.addAttribute("apps", EnumSet.allOf(App.class));
        map.addAttribute("appsMap", EnumSet.allOf(App.class).stream().collect(toMap(a -> a, a -> new BeanMap(a))));

        map.addAttribute("decodableEncoders", PasswordEncoderConfig.NONLEGACY_DECODABLE_ENCODERS);
        map.addAttribute("nonDecodableEncoders", PasswordEncoderConfig.NONLEGACY_NONDECODABLE_ENCODERS);
        map.addAttribute("encoderAliases", ENCODER_ALIASES);

        map.addAttribute("preferredEncoderNonDecodableAllowed", securityService.getPreferredPasswordEncoder(true));
        map.addAttribute("preferredEncoderDecodableOnly", securityService.getPreferredPasswordEncoder(false));

        map.addAttribute("ldapAuthEnabledForUser", userInDb.isLdapAuthenticated());
        map.addAttribute("adminRole", userInDb.isAdminRole());

        // admin restricted, installation-wide settings
        if (userInDb.isAdminRole() && !map.containsAttribute("adminControls")) {
            map.addAttribute("adminControls",
                    new AdminControls(
                            securityService.checkLegacyCredsPresent(),
                            securityService.checkOpenCredsPresent(),
                            securityService.checkDefaultAdminCredsPresent(),
                            settingsService.getJWTKey(),
                            settingsService.getEncryptionPassword(),
                            settingsService.getEncryptionSalt(),
                            settingsService.getNonDecodablePasswordEncoder(),
                            settingsService.getDecodablePasswordEncoder(),
                            settingsService.getPreferNonDecodablePasswords()
                            ));
        }
    }

    @PostMapping
    protected String createNewCreds(Principal user,
            @ModelAttribute("newCreds") @Validated(value = { Default.class, CredentialCreateChecks.class }) CredentialsCommand cc,
            BindingResult br, RedirectAttributes redirectAttributes, ModelMap map) {
        if (br.hasErrors()) {
            map.addAttribute("open_CreateCredsDialog", true);
            return "credentialsSettings";
        }

        boolean success = securityService.createCredential(user.getName(), cc, "Created by user");

        redirectAttributes.addFlashAttribute("settings_toast", success);

        return "redirect:credentialsSettings.view";
    }

    @PostMapping("update")
    protected String updateCreds(Principal user,
            @ModelAttribute("command") @Validated(value = { Default.class, CredentialUpdateChecks.class }) CredentialsManagementCommand cmc,
            BindingResult br, RedirectAttributes redirectAttributes) {
        if (br.hasErrors()) {
            return "credentialsSettings";
        }

        boolean result = securityService.updateCredentials(user.getName(), cmc, "User updated", false);

        redirectAttributes.addFlashAttribute("settings_toast", result);

        return "redirect:/credentialsSettings.view";
    }

    @PostMapping(path = "/admin")
    protected String adminControls(Authentication user, @Validated @ModelAttribute("adminControls") AdminControls ac,
            BindingResult br, RedirectAttributes redirectAttributes, ModelMap map) {
        if (br.hasErrors()) {
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.adminControls", br);
            redirectAttributes.addFlashAttribute("adminControls", ac);
            return "redirect:/credentialsSettings.view";
        }

        if (map.getAttribute("adminRole") == null || !((boolean) map.getAttribute("adminRole"))) {
            return "redirect:/credentialsSettings.view";
        }

        boolean success = true;

        if (ac.getMigrateLegacyCredsToNonLegacyDefault()) {
            success = securityService.migrateLegacyCredsToNonLegacy(false);
        } else if (ac.getMigrateLegacyCredsToNonLegacyDecodableOnly()) {
            success = securityService.migrateLegacyCredsToNonLegacy(true);
        }

        boolean saveSettings = false;
        if (ac.getJwtKeyChanged()) {
            settingsService.setJWTKey(ac.getJwtKey());
            saveSettings = true;
        }

        if (ac.getEncryptionKeyChanged()) {
            settingsService.setEncryptionPassword(ac.getEncryptionKey());
            saveSettings = true;
        }

        if (ac.getNonDecodableEncoderChanged()) {
            settingsService.setNonDecodablePasswordEncoder(ac.getNonDecodableEncoder());
            saveSettings = true;
        }

        if (ac.getDecodableEncoderChanged()) {
            settingsService.setDecodablePasswordEncoder(ac.getDecodableEncoder());
            saveSettings = true;
        }

        if (ac.getNonDecodablePreferenceChanged()) {
            settingsService.setPreferNonDecodablePasswords(ac.getPreferNonDecodable());
            saveSettings = true;
        }

        if (saveSettings) {
            settingsService.save();
        }

        redirectAttributes.addFlashAttribute("settings_toast", success);

        return "redirect:/credentialsSettings.view";
    }
}
