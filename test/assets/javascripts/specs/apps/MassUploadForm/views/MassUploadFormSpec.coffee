define [
  'jquery'
  'backbone'
  'apps/MassUploadForm/views/MassUploadForm'
  'i18n'
  'apps/ImportOptions/app'
], ($, Backbone, MassUploadForm, i18n, ImportOptionsApp) ->
  describe 'apps/MassUploadForm/views/MassUploadForm', ->
    model = undefined
    view = undefined
    uploadViewRenderSpy = undefined

    MockUpload = Backbone.Model.extend(
      isFullyUploaded: ->
        @get('isFullyUploaded')
    )

    mockFileInput = ->
      # A file input can't have its "files" attribute set. But we need
      # to mock that, so we'll replace it with a div.
      #
      # Returns the mock file input
      $fileInput = $('<div class="invisible-file-input"></div>')
      view.$el.find('.invisible-file-input').replaceWith($fileInput)
      $fileInput

    beforeEach ->
      i18n.reset_messages
        'views.DocumentSet._massUploadForm.upload_prompt': 'upload_prompt'
        'views.DocumentSet._massUploadForm.choose_options': 'choose_options'
        'views.DocumentSet._massUploadForm.drop_target': 'drop_target'
        'views.DocumentSet._massUploadForm.minimum_files': 'minimum_files'
        'views.DocumentSet._massUploadForm.wait_for_import': 'wait_for_import'
        'views.DocumentSet._massUploadForm.cancel': 'cancel'
        'views.DocumentSet._uploadProgress.uploading': 'uploading'
        'views.DocumentSet._massUploadForm.confirm_cancel.title': 'title'
        'views.DocumentSet._massUploadForm.confirm_cancel.prompt': 'prompt'
        'views.DocumentSet._massUploadForm.confirm_cancel.back_button': 'back_button'
        'views.DocumentSet._massUploadForm.confirm_cancel.confirm_button': 'confirm_button'

      clearAjaxRequests()

      uploadViewClass = Backbone.View.extend(tagName: 'li')
      uploadViewRenderSpy = spyOn(uploadViewClass.prototype, 'render').andCallThrough()
      model = new Backbone.Model
      model.uploads = new Backbone.Collection
      model.abort = jasmine.createSpy()

      # fake the import options menu
      $('body').append('<div class="nav-buttons"><ul><li><a href="#foo">foo</a></li></ul></div>')

      view = new MassUploadForm
        model: model
        uploadViewClass: uploadViewClass
        supportedLanguages: [ {code: "en", name: "English"} ]
        defaultLanguageCode: 'en'
      $.extend model,
        addFiles: jasmine.createSpy()

    afterEach ->
      $('div.nav-buttons').remove()

    describe 'init', ->
      it 'cancels previous uploads', ->
        # remove this when we add resumable uploads
        # expect(mostRecentAjaxRequest().url).toEqual('/files')
        # expect(mostRecentAjaxRequest().method).toEqual('DELETE')

    describe 'render', ->
      beforeEach ->
        view.render()

      it 'has a file input', ->
        expect(view.$el.find('input[type=file]').length).toEqual(1)

      it 'only shows pdf files by default', ->
        expect(view.$el.find('input[type=file]').attr('accept')).toEqual('application/pdf')

      it 'has an empty state', ->
        expect(view.$el.text()).toMatch('drop_target')

    describe 'model add event', ->
      beforeEach ->
        view.render()
        model.uploads.add(new Backbone.Model())

      it 'does not yet enable the submit button', ->
        waits(0) # We defer the add, as it seems more responsive that way
        expect(view.$('.choose-options')).toBeDisabled()

      describe 'with 3 or more uploads', ->
        beforeEach ->
          model.uploads.add(new Backbone.Model())
          model.uploads.add(new Backbone.Model())
          waits(0) # We defer the add, as it seems more responsive that way

        it 'adds an uploadView when a file is added', ->
          runs ->
            expect(uploadViewRenderSpy).toHaveBeenCalled()
            expect(view.$el.find('.files li').length).toEqual(3)

        it 'renders a progress bar', ->
          runs ->
            expect(view.$el).toContain('progress')

        describe 'submit button', ->
          it 'enables the submit button', ->
            expect(view.$('.choose-options')).not.toBeDisabled()

          it 'shows a modal with the import options app', ->
            spyOn(ImportOptionsApp, 'addHiddenInputsThroughDialog')
            view.$('.choose-options').click()
            expect(ImportOptionsApp.addHiddenInputsThroughDialog).toHaveBeenCalledWith(
              jasmine.any(HTMLElement),
              supportedLanguages: jasmine.any(Array)
              defaultLanguageCode: 'en'
              excludeOptions: ['split_documents']
              callback: jasmine.any(Function)
            )

          describe 'after selecting options', ->
            it 'disables the "set options" button', ->
              spyOn(ImportOptionsApp, 'addHiddenInputsThroughDialog').andCallFake( (el, options) -> options.callback() )
              view.$('.choose-options').click()
              expect(view.$('button.choose-options')).toBeDisabled()
              expect(view.$('button.select-files')).toBeDisabled()
              expect(view.$(':file')).toBeDisabled()

    describe 'dom events', ->
      it 'changes the button hover state when the invisible input is hovered', ->
        view.render()
        view.$(':file').trigger('mouseenter')
        expect(view.$('button')).toHaveClass('hover')
        view.$(':file').trigger('mouseleave')
        expect(view.$('button')).not.toHaveClass('hover')

    describe 'uploading', ->
      fileList = undefined
      $fileInput = undefined

      beforeEach ->
        view.render()

        fileList = [ {}, {} ]  # two things
        $fileInput = mockFileInput()
        $fileInput[0].files = fileList
        $fileInput.trigger('change')

      it 'queues files for uploading', ->
        expect(model.addFiles).toHaveBeenCalledWith(fileList)

      it 'clears the file input once files have been queued', ->
        expect($fileInput[0].value).toEqual('')

    describe 'buttons', ->
      beforeEach ->
        view.render()

      it 'has an add files button', ->
        expect(view.$el.text()).toMatch(/upload_prompt/)

      describe 'choose options button', ->
        it 'has a "finished selecting files" button', ->
          expect(view.$('.choose-options').length).toEqual(1)
          expect(view.$el.text()).toMatch(/choose_options/)

        it 'is disabled with no files selected', ->
          expect(view.$('.choose-options')).toBeDisabled()

        describe 'after selecting options', ->
          beforeEach ->
            # add some finished uploads
            model.uploads.add(new Backbone.Model)
            model.uploads.add(new Backbone.Model)
            model.uploads.add(new Backbone.Model)
            model.set(status: 'waiting')

            spyOn(ImportOptionsApp, 'addHiddenInputsThroughDialog').andCallFake( (el, options) -> options.callback() )
            view.$('.choose-options').click()

          it 'disables itself and the select files button', ->
            expect(view.$('button.choose-options')).toBeDisabled()
            expect(view.$('button.select-files')).toBeDisabled()
            expect(view.$(':file')).toBeDisabled()

          it 'shows the finished importing text', ->
            expect(view.$('.wait-for-import')).toHaveCss(display: 'block')

      describe 'cancel button', ->
        beforeEach ->
          jasmine.Clock.useMock()
          spyOn(view, 'getHash').andReturn('#import-from-mass-upload')

        it 'has a cancel button with the correct message', ->
          expect(view.$('.cancel').length).toEqual(1)
          expect(view.$el.text()).toMatch(/cancel/)

        describe 'without an upload', ->
          it 'does not ask for confirmation', ->
            modalSpy = spyOn($.fn, 'modal')
            view.$('.cancel').click()
            expect(modalSpy).not.toHaveBeenCalledWith('show')

        describe 'with an upload', ->
          beforeEach ->
            model.uploads.add(new MockUpload(status: 'uploading'))

          it 'asks for confirmation', ->
            modalSpy = spyOn($.fn, 'modal')
            view.$('.cancel').click()
            expect(modalSpy).toHaveBeenCalledWith('show')
            expect(modalSpy.mostRecentCall.object.selector).toEqual('#cancel-modal')

          it 'closes the modal', ->
            view.$('.cancel').click()
            jasmine.Clock.tick(100)
            modalSpy = spyOn($.fn, 'modal')
            view.$('.cancel-upload').click()
            expect(modalSpy).toHaveBeenCalledWith('hide')
            expect(modalSpy.mostRecentCall.object.selector).toEqual('#cancel-modal')

          it 'removes files in the "correct" way', ->
            model.uploads.add(new MockUpload(status: 'waiting', isFullyUploaded: true))
            model.uploads.add(new MockUpload(status: 'uploading'))
            model.uploads.add(new MockUpload(status: 'waiting'))
            view.$('.cancel').click()
            jasmine.Clock.tick(100)
            view.$('.cancel-upload').click()
            expect(model.abort).toHaveBeenCalled()

          it 'sends a cancel message to the server', ->
            clearAjaxRequests()
            view.$('.cancel').click()
            jasmine.Clock.tick(100)
            view.$('.cancel-upload').click()

            request = mostRecentAjaxRequest()
            expect(request.method).toEqual('DELETE')
            expect(request.url).toEqual('/files')

          it 'returns to the document sets pane', ->
            spyOn(view, 'setHash')
            view.$('.cancel').click()
            jasmine.Clock.tick(100)
            view.$('.cancel-upload').click()

            expect(view.setHash).toHaveBeenCalledWith('')

      describe 'cancelling with the import options menu', ->
        beforeEach ->
          jasmine.Clock.useMock()
          spyOn(view, 'getHash').andReturn('#import-from-mass-upload')

        it 'goes to the correct place', ->
          spyOn(view, 'setHash')
          $('div.nav-buttons li a').click()
          jasmine.Clock.tick(100)
          view.$('.cancel-upload').click()

          expect(view.setHash).toHaveBeenCalledWith('#foo')

    describe 'finishing upload', ->
      submitSpy = undefined

      beforeEach ->
        submitSpy = spyOn($.fn, 'submit')
        view.render()

        # add 3 uploads
        model.uploads.add(new Backbone.Model)
        model.uploads.add(new Backbone.Model)
        model.uploads.add(new Backbone.Model)

      it 'submits the form when uploading is finished and options are chosen', ->
        # finish the uploads
        model.set(status: 'waiting')

        # choose options
        spyOn(ImportOptionsApp, 'addHiddenInputsThroughDialog').andCallFake( (el, options) -> options.callback() )
        view.$('.choose-options').click()

        expect(submitSpy).toHaveBeenCalled()

      it 'submits the form when options are set before the upload is done', ->
        # uploads are in progress
        model.set(status: 'uploading')

        # choose options
        spyOn(ImportOptionsApp, 'addHiddenInputsThroughDialog').andCallFake( (el, options) -> options.callback() )
        view.$('.choose-options').click()

        # finish uploading
        model.set(status: 'waiting')

        expect(submitSpy).toHaveBeenCalled()

      it 'does not submit the form until the upload is finished', ->
        spyOn(ImportOptionsApp, 'addHiddenInputsThroughDialog').andCallFake( (el, options) -> options.callback() )
        view.$('.choose-options').click()

        expect(submitSpy).not.toHaveBeenCalled()

      it 'hides the progress bar when the upload finishes', ->
        # finish uploads
        model.set(status: 'waiting')

        expect(view.$el.find('.progress-bar').css('display')).toEqual('none')

      it 'shows the progress bar when adding another file', ->
        # finish uploads
        model.set(status: 'waiting')

        # now, add an unfinished upload
        model.uploads.add(new Backbone.Model)
        model.set(status: 'uploading')

        expect(view.$el.find('.progress-bar').css('display')).toEqual('block')
