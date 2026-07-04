
window.BYD = window.BYD || {};

const triggerIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z"/></svg>';
const editIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>';
const deleteIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';
const infoIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>'

BYD.automations = {
    automations: {},
    schema: {},
    formData: {},
    editingId: null,

    init() {
        this.loadAutomations();
        this.loadAutomationSchema();
    },

    async loadAutomations() {
        try {
            const resp = await fetch('/api/automations/list');
            const data = await resp.json();
            if (data) {
                this.automations = data;
            } else {
                this.automations = {};
            }
        } catch (e) {
            console.warn('[Automations] Failed to load automations:', e);
            this.automations = {};
        }
        this.render();
    },

    async loadAutomationSchema() {
        try {
            const resp = await fetch('/api/automations/schema');
            const data = await resp.json();
            if (data) {
                this.schema = data;
            } else {
                this.schema = [];
            }
        } catch (e) {
            console.warn('[Automations] Failed to load schema:', e);
            this.schema = [
  {
    "id": "triggers",
    "label": "On Change",
    "description": "What actions to run when this automation is triggered. These actions will run in order and can be slightly delayed.",
    "options": [
      {
        "id": "power",
        "label": "Vehicle Power",
        "type": "event",
        "variables": [],
        "description": "The current power state of the vehicle. ACC state is when only the vehicle accessories are powered on."
      },
      {
        "id": "gear",
        "label": "Gear",
        "type": "event",
        "variables": [],
        "description": "The currently selected gear."
      }
    ],
    "required": 1
  },
  {
    "id": "conditions",
    "label": "If",
    "description": "What conditions to check when this automation is triggered. If the conditions no longer match when starting the actions, the automation will be cancelled. The conditions do not need to match the triggers.",
    "options": [
      {
        "id": "power",
        "label": "Vehicle Power",
        "type": "event",
        "variables": [],
        "description": "The current power state of the vehicle. ACC state is when only the vehicle accessories are powered on.",
        "comparator": {
          "id": "comparator",
          "label": "Compare",
          "type": "enum",
          "options": [
            {
              "id": "eq",
              "label": "Equal To"
            },
            {
              "id": "neq",
              "label": "Not Equal To"
            }
          ]
        },
        "value": {
          "id": "state",
          "label": "State",
          "type": "enum",
          "options": [
            {
              "id": "off",
              "label": "Off"
            },
            {
              "id": "acc",
              "label": "ACC"
            },
            {
              "id": "on",
              "label": "On"
            }
          ]
        }
      },
      {
        "id": "gear",
        "label": "Gear",
        "type": "event",
        "variables": [],
        "description": "The currently selected gear.",
        "comparator": {
          "id": "comparator",
          "label": "Compare",
          "type": "enum",
          "options": [
            {
              "id": "eq",
              "label": "Equal To"
            },
            {
              "id": "neq",
              "label": "Not Equal To"
            }
          ]
        },
        "value": {
          "id": "gear",
          "label": "Gear",
          "type": "enum",
          "options": [
            {
              "id": "p",
              "label": "Park"
            },
            {
              "id": "r",
              "label": "Reverse"
            },
            {
              "id": "n",
              "label": "Neutral"
            },
            {
              "id": "d",
              "label": "Drive"
            },
            {
              "id": "m",
              "label": "Manual"
            },
            {
              "id": "s",
              "label": "Sport"
            }
          ]
        }
      }
    ],
    "required": 0
  },
  {
    "id": "delay",
    "label": "Delay (seconds)",
    "type": "int",
    "min": 0,
    "max": 86400,
    "description": "How long to delay in seconds before triggering the actions. If the conditions change before the delay has complete, the actions will not trigger."
  },
  {
    "id": "actions",
    "label": "Then",
    "description": "What actions to run when this automation is triggered. These actions will run in order and can be slightly delayed.",
    "options": [
      {
        "id": "adas_slw",
        "label": "Set Speed Limit Warning State",
        "type": "vehicle-control",
        "variables": [
          {
            "id": "action",
            "label": "Action",
            "type": "enum",
            "options": [
              {
                "id": "off",
                "label": "Off"
              },
              {
                "id": "on",
                "label": "On"
              }
            ]
          }
        ],
        "description": "The speed limit warning state. This control does not work while the vehicle is powered off."
      },
      {
        "id": "sunshade",
        "label": "Control Sunshade",
        "type": "vehicle-control",
        "variables": [
          {
            "id": "action",
            "label": "Action",
            "type": "enum",
            "options": [
              {
                "id": "close",
                "label": "Close"
              },
              {
                "id": "open",
                "label": "Open"
              },
              {
                "id": "stop",
                "label": "Stop"
              }
            ]
          }
        ],
        "description": "Sunshade control. Stop will cause the sunshade to stop opening\/closing and stay at the current position. This control does not work while the vehicle is powered off."
      },
      {
        "id": "notification",
        "label": "Send Notification",
        "type": "notification",
        "variables": [
          {
            "id": "message",
            "label": "Message",
            "type": "string",
            "maxLength": 150
          }
        ],
        "description": "Send push notification. This will send a push notification to installed apps with notifications enabled."
      }
    ],
    "required": 1
  }
];
        }
        this.render();
        this.renderForm();
    },

    render() {
        const list = document.getElementById('automationList');
        const empty = document.getElementById('emptyState');
        if (!list || !empty) return;

        if (Object.keys(this.automations).length === 0) {
            list.innerHTML = '';
            empty.style.display = 'block';
            return;
        }

        empty.style.display = 'none';
        list.innerHTML = '';
        for (let [key, automation] of Object.entries(this.automations)) {
            list.append(this.createAutomationElement(key, automation));
        }
    },

    createAutomationElement(key, automation) {
        const automationDiv = document.createElement('div');
        automationDiv.classList.add(`${key}-card`, 'card', automation.disabled ? 'disabled': 'enabled');

        const automationHeader = document.createElement('div');
        automationHeader.classList.add(`${key}-header`, 'header');
        automationDiv.append(automationHeader);

        const automationInfo = document.createElement('div');
        automationInfo.classList.add(`${key}-info`, 'info');
        automationInfo.innerHTML = this.buildAutomationText(automation);
        automationHeader.append(automationInfo);

        const automationActions = document.createElement('div');
        automationActions.classList.add(`${key}-actions`, 'actions');
        automationHeader.append(automationActions);

        const triggerBtn = document.createElement('button');
        triggerBtn.classList.add(`${key}-action`, 'action', 'icon-btn');
        triggerBtn.innerHTML = triggerIcon;
        triggerBtn.addEventListener('click', () => this.triggerAutomation(key));
        automationActions.append(triggerBtn);

        const editBtn = document.createElement('button');
        editBtn.classList.add(`${key}-action`, 'action', 'icon-btn');
        editBtn.innerHTML = editIcon;
        editBtn.addEventListener('click', () => this.showForm(key, automation));
        automationActions.append(editBtn);

        const deleteBtn = document.createElement('button');
        deleteBtn.classList.add(`${key}-action`, 'action', 'icon-btn', 'danger');
        deleteBtn.innerHTML = deleteIcon;
        deleteBtn.addEventListener('click', () => this.deleteAutomation(key));
        automationActions.append(deleteBtn);

        const automationBody = document.createElement('div');
        automationBody.classList.add(`${key}-body`, 'body');
        automationDiv.append(automationBody);

        const statusText = document.createElement('div');
        statusText.classList.add(`${key}-status-text`, 'status-text');
        automationBody.append(statusText);

        const statusDot = document.createElement('span');
        statusDot.classList.add(`${key}-status-dot`, 'status-dot', automation.disabled ? 'off' : 'connected');
        statusText.append(statusDot);

        const statusMessage = document.createElement('span');
        statusText.classList.add(`${key}-status-message`, 'status-message');
        statusMessage.innerHTML = BYD.i18n.t(`common.${automation.disabled ? 'disabled' : 'enabled'}`);
        statusText.append(statusMessage);

        const statusToggle = document.createElement('label');
        statusToggle.classList.add(`${key}-toggle-switch`, 'toggle-switch');
        automationBody.append(statusToggle);

        const statusCheckbox = document.createElement('input');
        statusCheckbox.type = 'checkbox';
        statusCheckbox.checked = !automation.disabled;
        statusCheckbox.addEventListener('change', () => this.disableAutomation(key, !statusCheckbox.checked));
        statusToggle.append(statusCheckbox);

        const statusSlider = document.createElement('span');
        statusSlider.classList.add(`${key}-toggle-slider`, 'toggle-slider');
        statusToggle.append(statusSlider);

        return automationDiv;
    },

    buildAutomationText(automation) {
        if (!this.schema) return BYD.i18n.t('automation.failed_to_load_schema');

        let result = '';
        try {
            for (const section of this.schema) {
                if (section.options?.length) {
                    const sectionData = automation[section.id] ?? [];
                    for (let i = 0; i < sectionData.length; i++) {
                        result += i === 0 ? section.label : BYD.i18n.t('automation.extra_row');
                        result += ' ';
                        const data = section.options.find(option => option.id === sectionData[i].type);
                        result += data.label + ' ';
                        if (data.variables?.length) result += '(';
                        for (const variable of data.variables ?? []) {
                            const option = variable.options && variable.options.find(option => option.id === sectionData[i].variables[variable.id]);
                            result += variable.label + '=' + (option?.label ?? sectionData[i].variables[variable.id]) + ',';
                        }
                        if (data.variables?.length) result = result.slice(0, result.length - 1) + ') ';

                        if (data.comparator && data.value) {
                            for (const variable of ['comparator', 'value']) {
                                const option = data[variable].options && data[variable].options.find(option => option.id === sectionData[i][variable]);
                                result += (option?.label ?? sectionData[i].variables[variable.id]) + ' ';
                            }
                        }
                    }
                } else {
                    result += section.label + '=' + automation[section.id];
                }
                result += '<br>'
            }
        } catch(e) {
            return BYD.i18n.t('automation.parse_error')
        }
        return result;
    },

    renderForm() {
        const grid = document.getElementById('formGrid');
        if (!grid) return;

        if (Object.keys(this.schema).length === 0) {
            grid.innerHTML = BYD.i18n.t('automation.failed_to_load_schema');
            return;
        }

        grid.innerHTML = '';
        for (let section of this.schema) {
            grid.append(this.createSection(section));
        }
    },

    createSection(section) {
        const sectionDiv = document.createElement('div');
        sectionDiv.classList.add(`${section.id}-section`, 'section');

        if (section.options?.length) {
            if (!this.formData[section.id]) this.formData[section.id] = [];
            while (this.formData[section.id].length < (section.required ?? 0)) this.formData[section.id].push({});
            sectionDiv.append(this.createRowLabel(section));
            if (section.description) {
                sectionDiv.append(this.createRowDescription(section));
            }
            for (let i = 0; i < this.formData[section.id].length; i++) {
                if (i > 0) {
                    sectionDiv.append(this.createRowLabel({ id: section.id, label: BYD.i18n.t('automation.extra_row') }));
                }
                const row = this.createRow(section, i);
                if (this.formData[section.id].length > (section.required ?? 0)) {
                    const deleteContainer = document.createElement('div');
                    deleteContainer.classList.add(`${section.id}-delete-container`, 'delete-container');

                    const deleteBtn = document.createElement('button');
                    deleteBtn.classList.add(`${section.id}-delete`, 'delete', 'icon-btn', 'danger');
                    deleteBtn.innerHTML = deleteIcon;
                    deleteBtn.addEventListener('click', () => {
                        this.formData[section.id].splice(i, 1);
                        this.renderForm();
                    });
                    deleteContainer.append(deleteBtn);
                    row.append(deleteContainer);
                }
                sectionDiv.append(row);
            }
            if (this.formData[section.id].length === 0) {
                sectionDiv.append(this.createRowElement(section));
            }
            const addBtn = document.createElement('button');
            addBtn.classList.add('btn', 'btn-secondary', `${section.id}-add-button`, 'add-button');
            addBtn.innerHTML = `${BYD.i18n.t('automation.add_row')} +`;
            addBtn.addEventListener('click', () => {
                this.formData[section.id].push({});
                this.renderForm();
            });
            sectionDiv.append(addBtn);
        } else {
            sectionDiv.append(this.createRowLabel(section));
            if (section.description) {
                sectionDiv.append(this.createRowDescription(section));
            }
            const row = this.createRowElement(section);
            row.append(this.createInput(section, this.formData[section.id] ?? section.min, (element, value) => {
                this.formData[section.id] = value;
            }));
            sectionDiv.append(row);
        }
        return sectionDiv;
    },

    createRow({ id, label, options }, index) {
        const row = this.createRowElement({ id });
        const inputs = document.createElement('div');
        inputs.classList.add(`${id}-inputs`, 'inputs');

        const typeSelectorContainer = document.createElement('div');
        typeSelectorContainer.classList.add(`${id}-type-selector`, 'type-selector');

        const eventListener = (element, value, selected) => {
            inputs.querySelectorAll('.variable-input').forEach(input => input.remove());
            typeSelectorContainer.querySelectorAll('.description-icon').forEach(description => description.remove());
            if (this.formData[id][index].type !== value) this.formData[id][index] = { type: value };
            if (!this.formData[id][index].variables) this.formData[id][index].variables = {};
            if (selected.description) {
                const icon = document.createElement('div');
                icon.classList.add(`${id}-description-tooltip`, 'description-icon');
                icon.innerHTML = infoIcon;
                icon.addEventListener('mouseover', () => {
                    const currentPosition = icon.getBoundingClientRect();
                    this.showDescriptionTooltip(selected, currentPosition.left, currentPosition.bottom + window.scrollY);
                });
                icon.addEventListener('mouseout', () => this.hideDescriptionTooltip());
                typeSelectorContainer.append(icon);
            }
            for (const variable of selected.variables ?? []) {
                const variableSelector = this.createInput(variable, this.formData[id][index].variables[variable.id], (element, value) => {
                    this.formData[id][index].variables[variable.id] = value;
                });
                variableSelector.classList.add(`${id}-input`, 'variable-input');
                inputs.append(variableSelector);
            }
            if (selected.comparator && selected.value) {
                for (const variable of ['comparator', 'value']) {
                    const selector = this.createInput(selected[variable], this.formData[id][index][variable], (element, value) => {
                        this.formData[id][index][variable] = value;
                    });
                    selector.classList.add(`${id}-input`, 'variable-input', variable);
                    inputs.append(selector);
                }
            }
        }

        const typeSelector = this.createEnumInput(this.getTypeEnum(id, options), this.formData[id][index].type, eventListener);
        typeSelectorContainer.prepend(typeSelector);

        inputs.prepend(typeSelectorContainer);

        row.append(inputs);

        return row;
    },

    createRowElement({ id }) {
        const row = document.createElement('div');
        row.classList.add(`${id}-row`, 'row');
        return row;
    },

    createRowLabel({ id, label }) {
        const rowLabel = document.createElement('span');
        rowLabel.classList.add(`${id}-label`, 'label');
        rowLabel.textContent = label;

        return rowLabel;
    },

    createRowDescription({ id, description }) {
        const rowDescription = document.createElement('span');
        rowDescription.classList.add(`${id}-description`, 'description');
        rowDescription.textContent = description;

        return rowDescription;
    },

    showDescriptionTooltip({ description }, x, y) {
        let tooltip = document.querySelector('.description-tooltip');
        if (!tooltip) {
            tooltip = document.createElement('div');
            tooltip.classList.add('description-tooltip');
            document.body.append(tooltip);
        }
        tooltip.innerHTML = description;
        tooltip.style.left = x + 'px';
        tooltip.style.top = y + 'px';
        const position = tooltip.getBoundingClientRect();
        if (position.right > window.innerWidth) x = Math.max(0, window.innerWidth - tooltip.offsetWidth);
        if (position.left < 0) x = 0;
        if (position.bottom > window.innerHeight) y = Math.max(0, window.innerHeight + window.scrollY - tooltip.offsetHeight);
        if (position.top < 0) y = window.scrollY;
        tooltip.style.left = x + 'px';
        tooltip.style.top = y + 'px';
        tooltip.classList.toggle('visible', true);
    },

    hideDescriptionTooltip() {
        let tooltip = document.querySelector('.description-tooltip');
        if (tooltip) {
            tooltip.classList.toggle('visible', false);
        }
    },

    getTypeEnum(id, options) {
        return {
            type: 'enum',
            label: 'Select',
            id,
            options,
        }
    },

    createInput(data, defaultValue, eventListener) {
        switch (data.type) {
            case 'enum': return this.createEnumInput(data, defaultValue, eventListener);
            case 'string': return this.createStringInput(data, defaultValue, eventListener);
            case 'int': return this.createIntInput(data, defaultValue, eventListener);
        }
    },

    createEnumInput(data, defaultValue, eventListener) {
        const selector = document.createElement('select');
        selector.classList.add('input', 'enum');
        const placeholder = document.createElement('option');
        placeholder.value = data.id;
        placeholder.textContent = data.label;
        placeholder.disabled = true;
        placeholder.selected = true;
        placeholder.hidden = true;
        selector.append(placeholder);
        for (const option of data.options) {
            const optionElement = document.createElement('option');
            optionElement.value = option.id;
            optionElement.textContent = option.label;
            selector.append(optionElement);
        }
        if (defaultValue) selector.value = defaultValue;
        // Add invalid class if the selected option is not within the options list
        const changeEvent = () => {
            const selected = data.options.find(option => option.id === selector.value);
            if (selected) {
                selector.classList.toggle('invalid', false);
                if (eventListener) eventListener(selector, selector.value, selected);
            } else {
                selector.classList.toggle('invalid', true);
            }
        }
        selector.addEventListener('change', changeEvent);
        changeEvent();
        return selector;
    },

    createStringInput(data, defaultValue, eventListener) {
        const maxLength = data.maxLength ?? 2147483647;
        const input = document.createElement(maxLength <= 30 ? 'input' : 'textarea');
        input.classList.add('input', 'string');
        input.placeholder = data.label;
        input.maxLength = maxLength;
        if (defaultValue) input.value = defaultValue;
        // Add invalid class if the selected value is longer than maxLength
        const changeEvent = () => {
            if (input.value.length <= maxLength) {
                input.classList.toggle('invalid', false);
                if (eventListener) eventListener(input, input.value);
            } else {
                input.classList.toggle('invalid', true);
            }
        }
        input.addEventListener('change', changeEvent);
        changeEvent();
        return input;
    },

    createIntInput(data, defaultValue, eventListener) {
        const input = document.createElement('input');
        input.classList.add('input', 'int');
        input.type = 'number';
        input.placeholder = data.label;
        input.min = data.min ?? 0;
        input.max = data.max ?? 2147483647;
        if (!isNaN(defaultValue)) input.value = defaultValue;
        // Add invalid class if the selected value is not within the min and max
        const changeEvent = () => {
            const value = parseInt(input.value);
            if (!isNaN(value) && value >= data.min && value <= data.max) {
                input.classList.toggle('invalid', false);
                if (eventListener) eventListener(input, value);
            } else {
                input.classList.toggle('invalid', true);
            }
        }
        input.addEventListener('change', changeEvent);
        changeEvent();
        return input;
    },

    _switchTab(id) {
        if (typeof window.OT_setActiveTab === 'function') window.OT_setActiveTab(id);
    },

    showForm(id, formData) {
        this.editingId = id;
        this.formData = formData;
        var titleEl = document.getElementById('formTitle');
        if (titleEl) {
            if (id) {
                titleEl.textContent = BYD.i18n.t('automation.edit_automation');
            } else {
                titleEl.textContent = BYD.i18n.t('automation.add_automation');
            }
        }
        this.renderForm();
        this._switchTab('add');
    },

    hideForm() {
        // Cancel returns to the Automations list. editingId is reset so a
        // subsequent tap on the empty-state CTA opens a fresh form.
        this.showForm(null, {});
        this._switchTab('automations');
    },

    async saveForm() {
        if (document.querySelectorAll('.form-grid .invalid').length) {
            this.toast(BYD.i18n.t('automation.invalid_form'), 'error');
        } else {
            try {
                const resp = await fetch(`/api/automations/automation${this.editingId ? '/' + this.editingId : ''}`, {
                    method: this.editingId ? 'PUT' : 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.formData)
                });
                const result = await resp.json();
                if (result.success) {
                    await this.loadAutomations();
                    this.hideForm();
                    return this.toast(BYD.i18n.t('toast.saved'), 'success');
                }
            } catch(e) {}
            this.toast(BYD.i18n.t('errors.save_failed'), 'error');
        }
    },

    async triggerAutomation(key) {
        try {
            const resp = await fetch(`/api/automations/test/${key}`, { method: 'POST' });
            const result = await resp.json();
            if (result.success) {
                return this.toast(BYD.i18n.t('automation.toast_triggered'), 'success');
            }
        } catch(e) {}
        this.toast(BYD.i18n.t('errors.generic'), 'error');
    },

    async deleteAutomation(key) {
        if (!confirm(BYD.i18n.t('confirm.delete'))) return;
        try {
            const resp = await fetch(`/api/automations/automation/${key}`, { method: 'DELETE' });
            const result = await resp.json();
            if (result.success) {
                await this.loadAutomations();
                return this.toast(BYD.i18n.t('toast.deleted'), 'success');
            }
        } catch(e) {}
        this.toast(BYD.i18n.t('errors.delete_failed'), 'error');
    },

    async disableAutomation(key, disabled) {
        try {
            const resp = await fetch(`/api/automations/disable/${key}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ disabled })
            });
            const result = await resp.json();
            if (result.success) {
                await this.loadAutomations();
                this.hideForm();
                return this.toast(BYD.i18n.t('toast.saved'), 'success');
            }
        } catch(e) {}
        this.toast(BYD.i18n.t('errors.save_failed'), 'error');
    },

    toast(message, type) {
        if (BYD.utils && BYD.utils.toast) {
            BYD.utils.toast(message, type === 'error' ? 'error' : 'success');
        } else {
            console.log('[Automations] ' + type + ': ' + message);
        }
    }
}

window.AutomationSettings = BYD.automations;
